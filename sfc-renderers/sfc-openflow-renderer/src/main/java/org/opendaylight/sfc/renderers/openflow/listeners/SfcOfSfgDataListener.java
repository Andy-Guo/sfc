/*
 * Copyright (c) 2014, 2017 Ericsson Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.sfc.renderers.openflow.listeners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.DataObjectModification;
import org.opendaylight.controller.md.sal.binding.api.DataTreeModification;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.sfc.provider.api.SfcInstanceIdentifiers;
import org.opendaylight.sfc.provider.api.SfcProviderServiceForwarderAPI;
import org.opendaylight.sfc.provider.api.SfcProviderServiceFunctionAPI;
import org.opendaylight.sfc.provider.api.SfcProviderServiceFunctionGroupAlgAPI;
import org.opendaylight.sfc.renderers.openflow.openflow.SfcOfFlowProgrammerInterface;
import org.opendaylight.sfc.renderers.openflow.sfg.GroupBucketInfo;
import org.opendaylight.sfc.renderers.openflow.utils.SfcOfBaseProviderUtils;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.common.rev151017.SfName;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.common.rev151017.SffName;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sf.rev140701.service.function.base.SfDataPlaneLocator;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sf.rev140701.service.functions.ServiceFunction;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sff.rev140701.service.function.forwarders.ServiceFunctionForwarder;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sff.rev140701.service.function.forwarders.service.function.forwarder.ServiceFunctionDictionary;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sfg.alg.rev150214.service.function.group.algorithms.ServiceFunctionGroupAlgorithm;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sfg.rev150214.service.function.group.entry.SfcServiceFunction;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sfg.rev150214.service.function.groups.ServiceFunctionGroup;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sl.rev140701.IpPortLocator;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sl.rev140701.MacAddressLocator;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sl.rev140701.data.plane.locator.LocatorType;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sl.rev140701.data.plane.locator.locator.type.Ip;
import org.opendaylight.yang.gen.v1.urn.cisco.params.xml.ns.yang.sfc.sl.rev140701.data.plane.locator.locator.type.Mac;
import org.opendaylight.yangtools.yang.binding.DataContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class has will be notified when changes are mad to Service function
 * group.
 *
 * @author Shlomi Alfasi (shlomi.alfasi@contextream.com)
 * @version 0.1
 * @since 2015-18-04
 */
public class SfcOfSfgDataListener extends SfcOfAbstractDataListener<ServiceFunctionGroup> {

    private final SfcOfFlowProgrammerInterface sfcOfFlowProgrammer;
    private final SfcOfBaseProviderUtils sfcOfProviderUtils;

    private static final Logger LOG = LoggerFactory.getLogger(SfcOfSfgDataListener.class);

    public SfcOfSfgDataListener(DataBroker dataBroker, SfcOfFlowProgrammerInterface sfcOfFlowProgrammer,
            SfcOfBaseProviderUtils sfcOfProviderUtils) {
        this.sfcOfFlowProgrammer = sfcOfFlowProgrammer;
        this.sfcOfProviderUtils = sfcOfProviderUtils;

        registerAsDataChangeListener(dataBroker, LogicalDatastoreType.CONFIGURATION,
                SfcInstanceIdentifiers.SFG_ENTRY_IID);
    }

    @Override
    public void onDataTreeChanged(Collection<DataTreeModification<ServiceFunctionGroup>> changes) {
        for (DataTreeModification<ServiceFunctionGroup> change: changes) {
            DataObjectModification<ServiceFunctionGroup> rootNode = change.getRootNode();
            switch (rootNode.getModificationType()) {
                case SUBTREE_MODIFIED:
                case WRITE:
                    ServiceFunctionGroup updatedGroup = rootNode.getDataAfter();
                    LOG.info("SfcOfSfgDataListener.onDataTreeChanged Update SFG {}", updatedGroup.getName());
                    buildGroup(updatedGroup, true);
                    break;
                case DELETE:
                    ServiceFunctionGroup deletedGroup = rootNode.getDataBefore();
                    LOG.info("SfcOfSfgDataListener.onDataChanged remove SFG {}", deletedGroup.getName());
                    buildGroup(deletedGroup, false);
                    break;
                default:
                    break;
            }
        }
    }

    private void buildGroup(ServiceFunctionGroup sfg, boolean isAdd) {
        List<SfcServiceFunction> sfs = sfg.getSfcServiceFunction();
        SfName sfName = new SfName(sfs.get(0).getName());
        ServiceFunction sf = SfcProviderServiceFunctionAPI.readServiceFunction(sfName);
        // assuming all SF's have the same SFF
        // should use the ovs id
        SffName sffName = sf.getSfDataPlaneLocator().get(0).getServiceFunctionForwarder();
        String sffNodeId;
        sffNodeId = getSffOpenFlowNodeName(sffName);

        if (sffNodeId == null) {
            LOG.warn("failed to find switch configuration: sffName: {}- \naborting", sffName);
            return;
        }

        ServiceFunctionGroupAlgorithm algorithm = SfcProviderServiceFunctionGroupAlgAPI
                .readServiceFunctionGroupAlg(sfg.getAlgorithm());

        List<GroupBucketInfo> bucketsInfo = new ArrayList<>();

        ServiceFunctionForwarder sff = SfcProviderServiceForwarderAPI.readServiceFunctionForwarder(sffName);

        int index = 0;
        for (SfcServiceFunction sfcServiceFunction : sfg.getSfcServiceFunction()) {
            sfName = new SfName(sfcServiceFunction.getName());
            sf = SfcProviderServiceFunctionAPI.readServiceFunction(sfName);
            ServiceFunctionDictionary sffSfDict = sfcOfProviderUtils.getSffSfDictionary(sff, sfName);
            String outPort = sfcOfProviderUtils.getDictPortInfoPort(sff, sffSfDict);
            bucketsInfo.add(buildBucket(sf, outPort, index));
            index++;
        }
        this.sfcOfFlowProgrammer.configureGroup(sffName.getValue(), sffNodeId, sfg.getName(), sfg.getGroupId().toJava(),
                algorithm.getAlgorithmType().getIntValue(), bucketsInfo, isAdd);
    }

    private GroupBucketInfo buildBucket(ServiceFunction sf, String outPort, int index) {
        GroupBucketInfo gbi = new GroupBucketInfo();
        gbi.setIndex(index);

        SfDataPlaneLocator sfDpl = sf.getSfDataPlaneLocator().get(0);
        gbi.setSfMac(getSfDplMac(sfDpl));
        gbi.setSfIp(getSfDplIp(sfDpl));
        gbi.setOutPort(outPort);

        return gbi;
    }

    private String getSfDplMac(SfDataPlaneLocator sfDpl) {
        String sfMac = null;

        LocatorType sffLocatorType = sfDpl.getLocatorType();
        Class<? extends DataContainer> implementedInterface = sffLocatorType.implementedInterface();

        LOG.debug("implementedInterface: {}", implementedInterface);
        // Mac/IP and possibly VLAN
        if (implementedInterface.equals(Mac.class) && ((MacAddressLocator) sffLocatorType).getMac() != null) {
            sfMac = ((MacAddressLocator) sffLocatorType).getMac().getValue();
        }

        return sfMac;
    }

    private String getSfDplIp(SfDataPlaneLocator sfDpl) {
        String sfIp = null;

        LocatorType sffLocatorType = sfDpl.getLocatorType();
        Class<? extends DataContainer> implementedInterface = sffLocatorType.implementedInterface();

        if (implementedInterface.equals(Ip.class) && ((IpPortLocator) sffLocatorType).getIp() != null) {
            sfIp = ((IpPortLocator) sffLocatorType).getIp().stringValue();
        }
        return sfIp;
    }

    private String getSffOpenFlowNodeName(final SffName sffName) {
        ServiceFunctionForwarder sff = SfcProviderServiceForwarderAPI.readServiceFunctionForwarder(sffName);
        return sfcOfProviderUtils.getSffOpenFlowNodeName(sff);
    }
}
