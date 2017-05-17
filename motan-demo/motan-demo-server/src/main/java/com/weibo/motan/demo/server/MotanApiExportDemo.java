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

package com.weibo.motan.demo.server;

import com.weibo.api.motan.common.MotanConstants;
import com.weibo.api.motan.config.ProtocolConfig;
import com.weibo.api.motan.config.RegistryConfig;
import com.weibo.api.motan.config.ServiceConfig;
import com.weibo.api.motan.util.MotanSwitcherUtil;
import com.weibo.motan.demo.service.MotanDemoService;
import com.weibo.motan.demo.service.MotanDemoService2;

import java.util.ArrayList;
import java.util.List;

/**
 * Motan服务提供者纯API调用的Demo
 * 研究Motan源码最好的入口（推荐）
 *
 * ====================启动一个Motan服务
 * 1、选择注册中心配置；
 * 2、选择协议配置；
 * 3、设置服务接口和实例
 * 4、启动服务器接收调用请求；
 * 5、服务地址注册到注册中心；
 *
 */
public class MotanApiExportDemo {

    public static void main(String[] args) throws InterruptedException {
        ServiceConfig<MotanDemoService> motanDemoService = new ServiceConfig<MotanDemoService>();

        // 设置接口及实现类
        motanDemoService.setInterface(MotanDemoService.class);
        motanDemoService.setRef(new MotanDemoServiceImpl());

        // 配置服务的group以及版本号
        motanDemoService.setGroup("motan-demo-rpc");
        motanDemoService.setVersion("1.0");
        motanDemoService.setShareChannel(true);
        // 配置注册中心直连调用，如果没有zookeeper环境，可以使用这种注册中心
        // RegistryConfig directRegistry = new RegistryConfig();
        // directRegistry.setRegProtocol("local");
        // directRegistry.setCheck("false"); //不检查是否注册成功
        // motanDemoService.setRegistry(directRegistry);

        // 配置ZooKeeper注册中心，// TODO 注意本地必须先启动Zookeeper
        RegistryConfig zookeeperRegistry = new RegistryConfig();
        zookeeperRegistry.setRegProtocol("zookeeper");
        zookeeperRegistry.setAddress("127.0.0.1:2181");
        motanDemoService.setRegistry(zookeeperRegistry);

        // 配置RPC协议
        ProtocolConfig protocol = new ProtocolConfig();
        // 此处setId设置的值必须与setExport方法设置的值对应，或者setExport设置的值包含id，
        // 如果：id= motan1，那么 export= motan1:port  或 export= motan1:port,motan2:port
        // 如果：id= motan2, 那么 export= motan2:port  或 export= motan1:port,motan2:port
        protocol.setId("motan");
        // 注意：协议配置中name属性就是URL类中的protocolName属性
        protocol.setName("motan");
        motanDemoService.setProtocol(protocol);

//        ProtocolConfig protocol2 = new ProtocolConfig();
//        protocol2.setId("motan");
//        // 注意：协议配置中name属性就是URL类中的protocolName属性
//        protocol2.setName("motan");
//        motanDemoService.setProtocol(protocol);

        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>();
        protocols.add(protocol);
//        protocols.add(protocol2);
        motanDemoService.setProtocols(protocols);

        motanDemoService.setExport("motan:8002");
        motanDemoService.export();


        ServiceConfig<MotanDemoService2> motanDemoService2 = new ServiceConfig<MotanDemoService2>();

//        // 设置接口及实现类
//        motanDemoService2.setInterface(MotanDemoService2.class);
//        motanDemoService2.setRef(new MotanDemoServiceImpl2());
//        // 配置服务的group以及版本号
//        motanDemoService2.setGroup("motan-demo-rpc");
//        motanDemoService2.setVersion("1.0");
//        motanDemoService2.setRegistry(zookeeperRegistry);
//        motanDemoService2.setProtocols(protocols);
//        motanDemoService2.setShareChannel(true);
//        motanDemoService2.setExport("motan:8002");
//
//        //暴露服务，源码研究入口
//        motanDemoService2.export();

        MotanSwitcherUtil.setSwitcherValue(MotanConstants.REGISTRY_HEARTBEAT_SWITCHER, true);

        System.out.println("server start...");
    }

}
