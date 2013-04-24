// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the 
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.network.element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.cloudstack.api.command.admin.internallb.ConfigureInternalLoadBalancerElementCmd;
import org.apache.cloudstack.api.command.admin.internallb.CreateInternalLoadBalancerElementCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLoadBalancerElementsCmd;
import org.apache.cloudstack.network.lb.InternalLoadBalancerVMManager;
import org.apache.cloudstack.network.lb.dao.ApplicationLoadBalancerRuleDao;
import org.apache.log4j.Logger;

import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.VirtualRouterProvider.VirtualRouterProviderType;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.VirtualRouterProviderDao;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.VirtualRouterElement;
import com.cloud.network.element.VirtualRouterProviderVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.router.VirtualRouter.Role;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.SearchCriteria2;
import com.cloud.utils.db.SearchCriteriaService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;

@Local(value = {NetworkElement.class})
public class InternalLoadBalancerElement extends AdapterBase implements LoadBalancingServiceProvider, InternalLoadBalancerElementService, IpDeployer{
    private static final Logger s_logger = Logger.getLogger(InternalLoadBalancerElement.class);
    protected static final Map<Service, Map<Capability, String>> capabilities = setCapabilities();
    private static InternalLoadBalancerElement internalLbElement = null;

    @Inject NetworkModel _ntwkModel;
    @Inject NetworkServiceMapDao _ntwkSrvcDao;
    @Inject DomainRouterDao _routerDao;
    @Inject VirtualRouterProviderDao _vrProviderDao;
    @Inject PhysicalNetworkServiceProviderDao _pNtwkSvcProviderDao;
    @Inject InternalLoadBalancerVMManager _internalLbMgr;
    @Inject ConfigurationManager _configMgr;
    @Inject AccountManager _accountMgr;
    @Inject ApplicationLoadBalancerRuleDao _appLbDao;
    
    protected InternalLoadBalancerElement() {
    }
    
    public static InternalLoadBalancerElement getInstance() {
        if ( internalLbElement == null) {
            internalLbElement = new InternalLoadBalancerElement();
        }
        return internalLbElement;
     }
    
    private boolean canHandle(Network config, Scheme lbScheme) {
        //works in Advance zone only
        DataCenter dc = _configMgr.getZone(config.getDataCenterId());
        if (dc.getNetworkType() != NetworkType.Advanced) {
            s_logger.trace("Not hanling zone of network type " + dc.getNetworkType());
            return false;
        }
        if (config.getGuestType() != Network.GuestType.Isolated || config.getTrafficType() != TrafficType.Guest) {
            s_logger.trace("Not handling network with Type  " + config.getGuestType() + " and traffic type " + config.getTrafficType());
            return false;
        }
        
        Map<Capability, String> lbCaps = this.getCapabilities().get(Service.Lb);
        if (!lbCaps.isEmpty()) {
            String schemeCaps = lbCaps.get(Capability.LbSchemes);
            if (schemeCaps != null && lbScheme != null) {
                if (!schemeCaps.contains(lbScheme.toString())) {
                    s_logger.debug("Scheme " + lbScheme.toString() + " is not supported by the provider " + this.getName());
                    return false;
                }
            }
        }
        
        if (!_ntwkModel.isProviderSupportServiceInNetwork(config.getId(), Service.Lb, getProvider())) {
            s_logger.trace("Element " + getProvider().getName() + " doesn't support service " + Service.Lb
                    + " in the network " + config);
            return false;
        }
        return true;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    @Override
    public Provider getProvider() {
        return Provider.InternalLbVm;
    }

    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {
        
        if (!canHandle(network, null)) {
            s_logger.trace("No need to implement " + this.getName());
            return true;
        }
        
        //1) Get all the Ips from the network having LB rules assigned
        List<String> ips = _appLbDao.listLbIpsBySourceIpNetworkIdAndScheme(network.getId(), Scheme.Internal);
        
        //2) Start those vms
        for (String ip : ips) {
            Ip sourceIp = new Ip(ip);
            List<? extends VirtualRouter> internalLbVms;
            try {
                internalLbVms = _internalLbMgr.deployInternalLbVm(network, sourceIp, dest, _accountMgr.getAccount(network.getAccountId()), null);
            } catch (InsufficientCapacityException e) {
                s_logger.warn("Failed to deploy element " + this.getName() + " for ip " + sourceIp + " due to:", e);
                return false;
            } catch (ConcurrentOperationException e) {
                s_logger.warn("Failed to deploy element " + this.getName() + " for ip " + sourceIp + " due to:", e);
                return false;
            }
            
            if ((internalLbVms == null) || (internalLbVms.size() == 0)) {
                throw new ResourceUnavailableException("Can't deploy " + this.getName() + " to handle LB rules",
                        DataCenter.class, network.getDataCenterId());
            }
        }
       
        return true;
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        
        if (!canHandle(network, null)) {
            s_logger.trace("No need to prepare " + this.getName());
            return true;
        }
        
        if (vm.getType() == VirtualMachine.Type.User) {
          //1) Get all the Ips from the network having LB rules assigned
            List<String> ips = _appLbDao.listLbIpsBySourceIpNetworkIdAndScheme(network.getId(), Scheme.Internal);
            
            //2) Start those vms
            for (String ip : ips) {
                Ip sourceIp = new Ip(ip);
                List<? extends VirtualRouter> internalLbVms;
                try {
                    internalLbVms = _internalLbMgr.deployInternalLbVm(network, sourceIp, dest, _accountMgr.getAccount(network.getAccountId()), null);
                } catch (InsufficientCapacityException e) {
                    s_logger.warn("Failed to deploy element " + this.getName() + " for ip " + sourceIp + " due to:", e);
                    return false;
                } catch (ConcurrentOperationException e) {
                    s_logger.warn("Failed to deploy element " + this.getName() + " for ip " + sourceIp +  " due to:", e);
                    return false;
                }
                
                if ((internalLbVms == null) || (internalLbVms.size() == 0)) {
                    throw new ResourceUnavailableException("Can't deploy " + this.getName() + " to handle LB rules",
                            DataCenter.class, network.getDataCenterId());
                }
            }
        }
        
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        List<? extends VirtualRouter> internalLbVms = _routerDao.listByNetworkAndRole(network.getId(), Role.INTERNAL_LB_VM);
        if (internalLbVms == null || internalLbVms.isEmpty()) {
            return true;
        }
        boolean result = true;
        for (VirtualRouter internalLbVm : internalLbVms) {
            result = result && _internalLbMgr.destroyInternalLbVm(internalLbVm.getId(),
                    context.getAccount(), context.getCaller().getId());
            if (cleanup) {
                if (!result) {
                    s_logger.warn("Failed to stop internal lb element " + internalLbVm + ", but would try to process clean up anyway.");
                }
                result = (_internalLbMgr.destroyInternalLbVm(internalLbVm.getId(),
                        context.getAccount(), context.getCaller().getId()));
                if (!result) {
                    s_logger.warn("Failed to clean up internal lb element " + internalLbVm);
                }
            }
        }
        return result;
    }

    @Override
    public boolean destroy(Network network, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        List<? extends VirtualRouter> internalLbVms = _routerDao.listByNetworkAndRole(network.getId(), Role.INTERNAL_LB_VM);
        if (internalLbVms == null || internalLbVms.isEmpty()) {
            return true;
        }
        boolean result = true;
        for (VirtualRouter internalLbVm : internalLbVms) {
            result = result && (_internalLbMgr.destroyInternalLbVm(internalLbVm.getId(),
                    context.getAccount(), context.getCaller().getId()));
        }
        return result;
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        VirtualRouterProviderVO element = _vrProviderDao.findByNspIdAndType(provider.getId(), 
                VirtualRouterProviderType.InternalLbVm);
        if (element == null) {
            return false;
        }
        return element.isEnabled();
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        VirtualRouterProviderVO element = _vrProviderDao.findByNspIdAndType(provider.getId(), 
                VirtualRouterProviderType.InternalLbVm);
        if (element == null) {
            return true;
        }
        long elementId = element.getId();
        List<DomainRouterVO> internalLbVms = _routerDao.listByElementId(elementId);
        boolean result = true;
        for (DomainRouterVO internalLbVm : internalLbVms) {
            result = result && (_internalLbMgr.destroyInternalLbVm(internalLbVm.getId(),
                    context.getAccount(), context.getCaller().getId()));
        }
        _vrProviderDao.remove(elementId);
        
        return result;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return true;
    }

    @Override
    public boolean verifyServicesCombination(Set<Service> services) {
        return true;
    }

    @Override
    public IpDeployer getIpDeployer(Network network) {
        return this;
    }

    @Override
    public boolean applyLBRules(Network network, List<LoadBalancingRule> rules) throws ResourceUnavailableException {
        //1) Get Internal LB VMs to destroy
        Set<Ip> vmsToDestroy = getVmsToDestroy(rules);
        
        //2) Get rules to apply
        Map<Ip, List<LoadBalancingRule>> rulesToApply = getLbRulesToApply(rules);
        s_logger.debug("Applying " + rulesToApply.size() + " on element " + this.getName());

 
        for (Ip sourceIp : rulesToApply.keySet()) {
            if (vmsToDestroy.contains(sourceIp)) {
                //2.1 Destroy internal lb vm
                List<? extends VirtualRouter> vms = _internalLbMgr.findInternalLbVms(network.getId(), sourceIp);
                if (vms.size() > 0) {
                    //only one internal lb per IP exists
                    try {
                        s_logger.debug("Destroying internal lb vm for ip " + sourceIp.addr() + " as all the rules for this vm are in Revoke state");
                        return _internalLbMgr.destroyInternalLbVm(vms.get(0).getId(), _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM),
                                _accountMgr.getUserIncludingRemoved(User.UID_SYSTEM).getId());
                    } catch (ConcurrentOperationException e) {
                        s_logger.warn("Failed to apply lb rule(s) for ip " + sourceIp.addr() + " on the element " + this.getName() + " due to:", e);
                        return false;
                    }
                }
            } else {
                //2.2 Start Internal LB vm per IP address
                List<? extends VirtualRouter> internalLbVms;
                try {
                    DeployDestination dest = new DeployDestination(_configMgr.getZone(network.getDataCenterId()), null, null, null); 
                    internalLbVms = _internalLbMgr.deployInternalLbVm(network, sourceIp, dest, _accountMgr.getAccount(network.getAccountId()), null);
                } catch (InsufficientCapacityException e) {
                    s_logger.warn("Failed to apply lb rule(s) for ip " + sourceIp.addr() + "on the element " + this.getName() + " due to:", e);
                    return false;
                } catch (ConcurrentOperationException e) {
                    s_logger.warn("Failed to apply lb rule(s) for ip " + sourceIp.addr() + "on the element " + this.getName() + " due to:", e);
                    return false;
                }
                
                if ((internalLbVms == null) || (internalLbVms.size() == 0)) {
                    throw new ResourceUnavailableException("Can't find/deploy internal lb vm to handle LB rules",
                            DataCenter.class, network.getDataCenterId());
                }
                 
                //2.3 Apply Internal LB rules on the VM
                if (!_internalLbMgr.applyLoadBalancingRules(network, rulesToApply.get(sourceIp), internalLbVms)) {
                    throw new CloudRuntimeException("Failed to apply load balancing rules for ip " + sourceIp.addr() + 
                            " in network " + network.getId() + " on element " + this.getName());
                }
            }
        }

        return true;    
    }

    protected Map<Ip, List<LoadBalancingRule>> getLbRulesToApply(List<LoadBalancingRule> rules) {
        //Group rules by the source ip address as NetworkManager always passes the entire network lb config to the element
        Map<Ip, List<LoadBalancingRule>> rulesToApply = groupBySourceIp(rules);
      
        return rulesToApply;
    }
    
    
    protected Set<Ip> getVmsToDestroy(List<LoadBalancingRule> rules) {
        //1) Group rules by the source ip address as NetworkManager always passes the entire network lb config to the element
        Map<Ip, List<LoadBalancingRule>> groupedRules = groupBySourceIp(rules);

        //2) Count rules in revoke state
        Set<Ip> vmsToDestroy = new HashSet<Ip>();
        
        for (Ip sourceIp : groupedRules.keySet()) {
            List<LoadBalancingRule> rulesToCheck = groupedRules.get(sourceIp);
            int revoke = 0;
            for (LoadBalancingRule ruleToCheck : rulesToCheck) {
                if (ruleToCheck.getState() == FirewallRule.State.Revoke){
                    revoke++;
                }
            }
            
            if (revoke == rulesToCheck.size()) {
                s_logger.debug("Have to destroy internal lb vm for source ip " + sourceIp);
                vmsToDestroy.add(sourceIp);
            } 
        }        
        return vmsToDestroy;
    }

    protected Map<Ip, List<LoadBalancingRule>> groupBySourceIp(List<LoadBalancingRule> rules) {
        Map<Ip, List<LoadBalancingRule>> groupedRules = new HashMap<Ip, List<LoadBalancingRule>>();
        for (LoadBalancingRule rule : rules) {
            Ip sourceIp = rule.getSourceIp();
            if (!groupedRules.containsKey(sourceIp)) {
                groupedRules.put(sourceIp, null);
            }
            
            List<LoadBalancingRule> rulesToApply = groupedRules.get(sourceIp);
            if (rulesToApply == null) {
                rulesToApply = new ArrayList<LoadBalancingRule>();
            }
            rulesToApply.add(rule);
            groupedRules.put(sourceIp, rulesToApply);
        }
        return groupedRules;
    }

    @Override
    public boolean validateLBRule(Network network, LoadBalancingRule rule) {
        List<LoadBalancingRule> rules = new ArrayList<LoadBalancingRule>();
        rules.add(rule);
        if (canHandle(network, rule.getScheme())) {
            List<DomainRouterVO> routers = _routerDao.listByNetworkAndRole(network.getId(), Role.INTERNAL_LB_VM);
            if (routers == null || routers.isEmpty()) {
                return true;
            }
            return VirtualRouterElement.validateHAProxyLBRule(rule);
        }
        return true;
    }

    @Override
    public List<LoadBalancerTO> updateHealthChecks(Network network, List<LoadBalancingRule> lbrules) {
        return null;
    }
    
    private static Map<Service, Map<Capability, String>> setCapabilities() {
        Map<Service, Map<Capability, String>> capabilities = new HashMap<Service, Map<Capability, String>>();

        // Set capabilities for LB service
        Map<Capability, String> lbCapabilities = new HashMap<Capability, String>();
        lbCapabilities.put(Capability.SupportedLBAlgorithms, "roundrobin,leastconn,source");
        lbCapabilities.put(Capability.SupportedLBIsolation, "dedicated");
        lbCapabilities.put(Capability.SupportedProtocols, "tcp, udp");
        lbCapabilities.put(Capability.SupportedStickinessMethods, VirtualRouterElement.getHAProxyStickinessCapability());
        lbCapabilities.put(Capability.LbSchemes, LoadBalancerContainer.Scheme.Internal.toString());

        capabilities.put(Service.Lb, lbCapabilities);
        return capabilities;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        cmdList.add(CreateInternalLoadBalancerElementCmd.class);
        cmdList.add(ConfigureInternalLoadBalancerElementCmd.class);
        cmdList.add(ListInternalLoadBalancerElementsCmd.class);
        return cmdList;
    }

    @Override
    public VirtualRouterProvider configureInternalLoadBalancerElement(long id, boolean enable) {
        VirtualRouterProviderVO element = _vrProviderDao.findById(id);
        if (element == null || element.getType() != VirtualRouterProviderType.InternalLbVm) {
            s_logger.debug("Can't find " + this.getName() + " element with network service provider id " + id +
                    " to be used as a provider for " + this.getName());
            return null;
        }

        element.setEnabled(enable);
        _vrProviderDao.persist(element);

        return element;
    }

    @Override
    public VirtualRouterProvider addInternalLoadBalancerElement(long ntwkSvcProviderId) {
        VirtualRouterProviderVO element = _vrProviderDao.findByNspIdAndType(ntwkSvcProviderId, VirtualRouterProviderType.InternalLbVm);
        if (element != null) {
            s_logger.debug("There is already an " + this.getName() + " with service provider id " + ntwkSvcProviderId);
            return null;
        }
        
        PhysicalNetworkServiceProvider provider = _pNtwkSvcProviderDao.findById(ntwkSvcProviderId);
        if (provider == null || !provider.getProviderName().equalsIgnoreCase(this.getName())) {
            throw new InvalidParameterValueException("Invalid network service provider is specified");
        }
        
        element = new VirtualRouterProviderVO(ntwkSvcProviderId, VirtualRouterProviderType.InternalLbVm);
        _vrProviderDao.persist(element);
        return element;
    }

    @Override
    public VirtualRouterProvider getInternalLoadBalancerElement(long id) {
        VirtualRouterProvider provider = _vrProviderDao.findById(id);
        if (provider.getType() != VirtualRouterProviderType.InternalLbVm) {
            throw new InvalidParameterValueException("Unable to find " + this.getName() + " by id");
        }
        return provider;
    }

    @Override
    public List<? extends VirtualRouterProvider> searchForInternalLoadBalancerElements(Long id, Long ntwkSvsProviderId, Boolean enabled) {

        SearchCriteriaService<VirtualRouterProviderVO, VirtualRouterProviderVO> sc = SearchCriteria2.create(VirtualRouterProviderVO.class);
        if (id != null) {
            sc.addAnd(sc.getEntity().getId(), Op.EQ, id);
        }
        if (ntwkSvsProviderId != null) {
            sc.addAnd(sc.getEntity().getNspId(), Op.EQ, ntwkSvsProviderId);
        }
        if (enabled != null) {
            sc.addAnd(sc.getEntity().isEnabled(), Op.EQ, enabled);
        }
        
        //return only Internal LB elements
        sc.addAnd(sc.getEntity().getType(), Op.EQ, VirtualRouterProvider.VirtualRouterProviderType.InternalLbVm);
        
        return sc.list();
    }

    @Override
    public boolean applyIps(Network network, List<? extends PublicIpAddress> ipAddress, Set<Service> services) throws ResourceUnavailableException {
        //do nothing here; this element just has to extend the ip deployer
        //as the LB service implements IPDeployerRequester
        return true;
    }

}
