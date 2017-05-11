/*
 *  Copyright 2009-2016 Weibo, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.weibo.api.motan.config;

import com.weibo.api.motan.common.MotanConstants;
import com.weibo.api.motan.common.URLParamType;
import com.weibo.api.motan.config.annotation.ConfigDesc;
import com.weibo.api.motan.config.handler.ConfigHandler;
import com.weibo.api.motan.core.extension.ExtensionLoader;
import com.weibo.api.motan.exception.MotanErrorMsgConstant;
import com.weibo.api.motan.exception.MotanFrameworkException;
import com.weibo.api.motan.exception.MotanServiceException;
import com.weibo.api.motan.registry.RegistryService;
import com.weibo.api.motan.rpc.Exporter;
import com.weibo.api.motan.rpc.URL;
import com.weibo.api.motan.util.ConcurrentHashSet;
import com.weibo.api.motan.util.LoggerUtil;
import com.weibo.api.motan.util.NetUtils;
import com.weibo.api.motan.util.StringTools;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author fishermen
 * @version V1.0 created at: 2013-5-16
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = -3342374271064293224L;
    private static ConcurrentHashSet<String> existingServices = new ConcurrentHashSet<String>();
    // 具体到方法的配置
    protected List<MethodConfig> methods;

    // 接口实现类引用
    private T ref;

    // service 对应的exporters，用于管理service服务的生命周期
    private List<Exporter<T>> exporters = new CopyOnWriteArrayList<Exporter<T>>();
    private Class<T> interfaceClass;
    private BasicServiceInterfaceConfig basicService;
    private AtomicBoolean exported = new AtomicBoolean(false);
    // service的用于注册的url，用于管理service注册的生命周期，url为regitry url，内部嵌套service url。
    private ConcurrentHashSet<URL> registereUrls = new ConcurrentHashSet<URL>();

    public static ConcurrentHashSet<String> getExistingServices() {
        return existingServices;
    }

    public Class<?> getInterface() {
        return interfaceClass;
    }

    public void setInterface(Class<T> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    public void setMethods(MethodConfig methods) {
        this.methods = Collections.singletonList(methods);
    }

    public void setMethods(List<MethodConfig> methods) {
        this.methods = methods;
    }

    public boolean hasMethods() {
        return this.methods != null && this.methods.size() > 0;
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    public List<Exporter<T>> getExporters() {
        return Collections.unmodifiableList(exporters);
    }

    protected boolean serviceExists(URL url) {
        return existingServices.contains(url.getIdentity());
    }

    public synchronized void export() {
        if (exported.get()) {
            LoggerUtil.warn(String.format("%s has already been expoted, so ignore the export request!", interfaceClass.getName()));
            return;
        }
        // 检查接口类是否正确，以及方法Bean列表是否对应接口类方法
        checkInterfaceAndMethods(interfaceClass, methods);
        // 解析RegisteryConfig类，获取注册相关信息存储到URL列表
        // 一个服务可能在多个注册中心注册
        List<URL> registryUrls = loadRegistryUrls();
        if (registryUrls == null || registryUrls.size() == 0) {
            throw new IllegalStateException("Should set registry config for service:" + interfaceClass.getName());
        }

        Map<String, Integer> protocolPorts = getProtocolAndPort();

        // 此处可以看出，一个服务可以在多种协议中暴露，只要协议的ID等于Service暴露的协议名
        for (ProtocolConfig protocolConfig : protocols) {
            Integer port = protocolPorts.get(protocolConfig.getId());
            if (port == null) {
                throw new MotanServiceException(String.format("Unknow port in service:%s, protocol:%s", interfaceClass.getName(),
                        protocolConfig.getId()));
            }
            doExport(protocolConfig, port, registryUrls);
        }

        afterExport();
    }

    public synchronized void unexport() {
        if (!exported.get()) {
            return;
        }
        try {
            ConfigHandler configHandler =
                    ExtensionLoader.getExtensionLoader(ConfigHandler.class).getExtension(MotanConstants.DEFAULT_VALUE);
            configHandler.unexport(exporters, registereUrls);
        } finally {
            afterUnexport();
        }
    }

    @SuppressWarnings("unchecked")
    private void doExport(ProtocolConfig protocolConfig, int port, List<URL> registryURLs) {
        String protocolName = protocolConfig.getName();
        if (protocolName == null || protocolName.length() == 0) {
            protocolName = URLParamType.protocol.getValue();
        }

        // 获取本机的IP地址
        String hostAddress = host;
        if (StringUtils.isBlank(hostAddress) && basicService != null) {
            hostAddress = basicService.getHost();
        }
        if (NetUtils.isInvalidLocalHost(hostAddress)) {
            hostAddress = getLocalHostAddress(registryURLs);
        }

        Map<String, String> map = new HashMap<String, String>();

        map.put(URLParamType.nodeType.getName(), MotanConstants.NODE_TYPE_SERVICE);
        map.put(URLParamType.refreshTimestamp.getName(), String.valueOf(System.currentTimeMillis()));

        // 把protocolConfig、basicService、extConfig、this的属性及值放入map中
        collectConfigParams(map, protocolConfig, basicService, extConfig, this);
        collectMethodConfigParams(map, this.getMethods());

        // 服务暴露的URL
        URL serviceUrl = new URL(protocolName, hostAddress, port, interfaceClass.getName(), map);

        if (serviceExists(serviceUrl)) {
            LoggerUtil.warn(String.format("%s configService is malformed, for same service (%s) already exists ", interfaceClass.getName(),
                    serviceUrl.getIdentity()));
            throw new MotanFrameworkException(String.format("%s configService is malformed, for same service (%s) already exists ",
                    interfaceClass.getName(), serviceUrl.getIdentity()), MotanErrorMsgConstant.FRAMEWORK_INIT_ERROR);
        }

        // 服务注册到注册中心的URL
        List<URL> urls = new ArrayList<URL>();

        // injvm 协议只支持注册到本地，其他协议可以注册到local、remote
        if (MotanConstants.PROTOCOL_INJVM.equals(protocolConfig.getId())) {
            URL localRegistryUrl = null;
            for (URL ru : registryURLs) {
                if (MotanConstants.REGISTRY_PROTOCOL_LOCAL.equals(ru.getProtocol())) {
                    localRegistryUrl = ru.createCopy();
                    break;
                }
            }
            if (localRegistryUrl == null) {
                localRegistryUrl =
                        new URL(MotanConstants.REGISTRY_PROTOCOL_LOCAL, hostAddress, MotanConstants.DEFAULT_INT_VALUE,
                                RegistryService.class.getName());
            }

            urls.add(localRegistryUrl);
        } else {
            for (URL ru : registryURLs) {
                urls.add(ru.createCopy());
            }
        }

        for (URL u : urls) {
            // 使注册的URL中内嵌服务暴露的URL信息，使用embed标识
            u.addParameter(URLParamType.embed.getName(), StringTools.urlEncode(serviceUrl.toFullStr()));
            registereUrls.add(u.createCopy());
        }
        // 真正的Handler放在META-INF/services/com.weibo.api.motan.config.handler.ConfigHandler文件里面，
        // ExtensionLoader通过反射的方式来获得真正Handler的实例
        // 这种方式加载实例，一方面可以对实例做缓存，另一方面可以方便地切换Handler
        ConfigHandler configHandler = ExtensionLoader.getExtensionLoader(ConfigHandler.class).getExtension(MotanConstants.DEFAULT_VALUE);

        exporters.add(configHandler.export(interfaceClass, ref, urls));

        initLocalAppInfo(serviceUrl);
    }

    private void afterExport() {
        exported.set(true);
        for (Exporter<T> ep : exporters) {
            existingServices.add(ep.getProvider().getUrl().getIdentity());
        }
    }

    private void afterUnexport() {
        exported.set(false);
        for (Exporter<T> ep : exporters) {
            existingServices.remove(ep.getProvider().getUrl().getIdentity());
            exporters.remove(ep);
        }
        exporters.clear();
        registereUrls.clear();
    }

    @ConfigDesc(excluded = true)
    public BasicServiceInterfaceConfig getBasicService() {
        return basicService;
    }

    public void setBasicService(BasicServiceInterfaceConfig basicService) {
        this.basicService = basicService;
    }

    public Map<String, Integer> getProtocolAndPort() {
        if (StringUtils.isBlank(export)) {
            throw new MotanServiceException("export should not empty in service config:" + interfaceClass.getName());
        }
        return ConfigUtil.parseExport(this.export);
    }

    @ConfigDesc(excluded = true)
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public AtomicBoolean getExported() {
        return exported;
    }

    public ConcurrentHashSet<URL> getRegistereUrls() {
        return registereUrls;
    }

}
