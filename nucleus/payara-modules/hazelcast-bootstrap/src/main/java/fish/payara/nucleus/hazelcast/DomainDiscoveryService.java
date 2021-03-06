/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2016-2021] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.nucleus.hazelcast;

import com.hazelcast.cluster.Address;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.spi.discovery.SimpleDiscoveryNode;
import com.hazelcast.spi.discovery.integration.DiscoveryService;
import com.hazelcast.spi.discovery.integration.DiscoveryServiceSettings;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Node;
import com.sun.enterprise.util.io.InstanceDirs;
import static fish.payara.nucleus.hazelcast.MemberAddressPicker.initAddress;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.grizzly.utils.Holder;
import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.ServerContext;

/**
 * Provides a Discovery SPI implementation for Hazelcast that uses knowledge
 * of the domain topology to build out the cluster and discover members
 * @since 5.0
 * @author steve
 */
public class DomainDiscoveryService implements DiscoveryService {
    private static Logger logger = Logger.getLogger(DomainDiscoveryService.class.getName());
    private final Holder.LazyHolder<InetAddress> chosenAddress =
            Holder.LazyHolder.lazyHolder(MemberAddressPicker::findMyAddressOrLocalHost);

    public DomainDiscoveryService(DiscoveryServiceSettings settings) {

    }

    @Override
    public void start() {
        //
    }

    @Override
    public Iterable<DiscoveryNode> discoverNodes() {
        logger.fine("Starting Domain Node Discovery");
        List<DiscoveryNode> nodes = new LinkedList<>();
        Domain domain = Globals.getDefaultHabitat().getService(Domain.class);
        ServerContext ctxt = Globals.getDefaultHabitat().getService(ServerContext.class);
        ServerEnvironment env = Globals.getDefaultHabitat().getService(ServerEnvironment.class);
        // add the DAS
        HazelcastRuntimeConfiguration hzConfig = domain.getExtensionByType(HazelcastRuntimeConfiguration.class);

        if (!env.isDas()) {
            try {
                // first get hold of the DAS host
                logger.fine("This is a Standalone Instance");
                String dasHost = hzConfig.getDASPublicAddress();
                if (dasHost == null || dasHost.isEmpty()) {
                    dasHost = hzConfig.getDASBindAddress();
                }
                dasHost = Optional.ofNullable(initAddress(dasHost, 0)).map(InetSocketAddress::getHostString).orElse("");

                if (dasHost.isEmpty()) {
                    // ok drag it off the properties file
                    logger.fine("Neither DAS Public Address or Bind Address is set in the configuration");
                    InstanceDirs instance = new InstanceDirs(env.getInstanceRoot());
                    Properties dasProps = new Properties();
                    dasProps.load(new FileInputStream(instance.getDasPropertiesFile()));
                    logger.fine("Loaded the das.properties file from the agent directory");
                    dasHost = dasProps.getProperty("agent.das.host");
                    // then do an IP lookup
                    dasHost = InetAddress.getByName(dasHost).getHostAddress();
                    logger.log(Level.FINE, "Loaded the das.properties file from the agent directory and found DAS IP {0}", dasHost);
                }

                if (dasHost.isEmpty() || dasHost.equals("127.0.0.1") || dasHost.equals("localhost")) {
                    logger.fine("Looks like the DAS IP is loopback or empty let's find the actual IP of this machine as that is where the DAS is");
                    nodes.add(new SimpleDiscoveryNode(new Address(chosenAddress.get(), Integer.parseInt(hzConfig.getDasPort()))));
                } else {
                    logger.log(Level.FINE, "DAS should be listening on {0}", dasHost);
                    nodes.add(new SimpleDiscoveryNode(new Address(dasHost, Integer.valueOf(hzConfig.getDasPort()))));
                }

                // also add all nodes we are aware of in the domain to see if we can get in using start port
                logger.fine("Also adding all known domain nodes and start ports in case the DAS is down");
                for (Node node : domain.getNodes().getNode()) {
                    InetAddress address = InetAddress.getByName(node.getNodeHost());
                    if (!address.isLoopbackAddress()) {
                        logger.log(Level.FINE, "Adding Node {0}", address);
                        nodes.add(new SimpleDiscoveryNode(new Address(address.getHostAddress(), Integer.valueOf(hzConfig.getStartPort()))));
                    }
                }
            } catch (IOException ex) {
                Logger.getLogger(DomainDiscoveryService.class.getName()).log(Level.SEVERE, null, ex);
            }

        } else if (env.isMicro()) {
            try {
                logger.log(Level.FINE, "We are Payara Micro therefore adding DAS {0}", hzConfig.getDASPublicAddress());
                // check if user has added locahost as unlikely to work
                String dasHost = Optional.ofNullable(initAddress(hzConfig.getDASPublicAddress(), 0))
                        .map(InetSocketAddress::getHostString).orElse("");
                if (hzConfig.getDasPort().equals("4848")) {
                    logger.log(Level.WARNING,"You have specified 4848 as the datagrid domain port however this is the default DAS admin port, the default domain datagrid port is 4900");
                }
                if (dasHost.isEmpty() || dasHost.equals("127.0.0.1") || dasHost.equals("localhost")) {
                    nodes.add(new SimpleDiscoveryNode(new Address(chosenAddress.get(), Integer.parseInt(hzConfig.getDasPort()))));
                } else {
                    nodes.add(new SimpleDiscoveryNode(new Address(InetAddress.getByName(dasHost), Integer.valueOf(hzConfig.getDasPort()))));
                }
            } catch (UnknownHostException | NumberFormatException ex) {
                Logger.getLogger(DomainDiscoveryService.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else {
            // ok this is the DAS
            logger.fine("We are the DAS therefore we will add all known nodes with start port as IP addresses to connect to");

            // Embedded runtimese don't have nodes
            if (domain.getNodes() == null) {
                nodes.add(new SimpleDiscoveryNode(new Address(chosenAddress.get(), Integer.parseInt(hzConfig.getStartPort()))));
            } else {
                for (Node node : domain.getNodes().getNode()) {
                    try {
                        InetAddress address = InetAddress.getByName(node.getNodeHost());
                        if (!address.isLoopbackAddress()) {
                            logger.log(Level.FINE, "Adding Node {0}", address);
                            nodes.add(new SimpleDiscoveryNode(new Address(address.getHostAddress(),
                                    Integer.valueOf(hzConfig.getStartPort()))));
                        } else {
                            // we need to add our IP address so add each interface address with the start port
                            nodes.add(new SimpleDiscoveryNode(new Address(chosenAddress.get(), Integer.parseInt(hzConfig.getStartPort()))));
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(DomainDiscoveryService.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }

        return nodes;
    }

    @Override
    public void destroy() {
        //
    }

    @Override
    public Map<String, String> discoverLocalMetadata() {
        return Collections.emptyMap();
    }
}
