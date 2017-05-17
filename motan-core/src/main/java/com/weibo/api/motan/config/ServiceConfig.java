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
        // 服务信息可能在多个注册中心注册
        List<URL> registryUrls = loadRegistryUrls();
        if (registryUrls == null || registryUrls.size() == 0) {
            throw new IllegalStateException("Should set registry config for service:" + interfaceClass.getName());
        }

        // 此处protocolPorts记录服务设置的协议配置，通过解析export属性得到；
        // 其中key为协议ID，val为端口号；
        Map<String, Integer> protocolPorts = getProtocolAndPort();

        // 服务可以在多种协议配置中暴露，暴露的标识通过export属性来设置；
        // export是一个字符串类型，格式为【协议ID:端口,协议ID:端口,...】；
        // 服务可以在不同的端口使用不同的协议配置来暴露，或者在不同的端口使用相同的协议配置暴露，
        // 或者在相同的端口使用不同的协议配置暴露；
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

    /**
     * 暴露服务：
     *      1、启动服务器进程；
     *      2、注册服务信息到服务器路由；
     *      3、注册服务信息到注册中心；
     *
     * @param protocolConfig 协议配置
     * @param port           服务所在服务器的端口
     * @param registryURLs   注册中心的URL信息列表
     */
    @SuppressWarnings("unchecked")
    private void doExport(ProtocolConfig protocolConfig, int port, List<URL> registryURLs) {
        //TODO 协议配置中name属性就是URL类中的protocolName属性
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

        // 暴露的服务URL，每一个服务URL在系统中都是唯一的
        URL serviceUrl = new URL(protocolName, hostAddress, port, interfaceClass.getName(), map);
        // 因为doExport方法放在协议配置轮询中，有可能上一次调用doExport成功暴露的URL还没有存入existingServices中；
        // 所以即使serviceUrl跟之前暴露的URL相同，serviceExists判断为false，因为之前的URL还没有存入existingServices
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
            // 注册URL中内嵌服务URL信息，使用embed标识
            u.addParameter(URLParamType.embed.getName(), StringTools.urlEncode(serviceUrl.toFullStr()));
            registereUrls.add(u.createCopy());
        }
        // 真正的Handler放在META-INF/services/com.weibo.api.motan.config.handler.ConfigHandler文件里面；
        // ExtensionLoader加载实例是Motan实现的Spi功能，需要在被加载的类前加上SpiMeta标识;
        // Spi功能有两方面的好处，一方面可以对实例做缓存，另一方面可以方便地切换实现类；
        ConfigHandler configHandler = ExtensionLoader.getExtensionLoader(ConfigHandler.class).getExtension(MotanConstants.DEFAULT_VALUE);

        exporters.add(configHandler.export(interfaceClass, ref, urls));

        initLocalAppInfo(serviceUrl);
    }

    private void afterExport() {
        exported.set(true);
        for (Exporter<T> ep : exporters) {
            // doExport方法中需要使用existingServices判断服务有没有暴露，
            // 如果有多个协议，那么协议循环中暴露的服务没有加到existingServices，
            // 不能立刻发现可能会重复()的暴露的服务
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
