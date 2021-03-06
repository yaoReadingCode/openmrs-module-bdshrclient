package org.openmrs.module.shrclient.service.impl;

import com.sun.syndication.feed.atom.Category;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Composition;
import org.hl7.fhir.dstu3.model.Period;
import org.ict4h.atomfeed.jdbc.JdbcConnectionProvider;
import org.ict4h.atomfeed.transaction.AFTransactionManager;
import org.openmrs.*;
import org.openmrs.api.EncounterService;
import org.openmrs.api.OrderService;
import org.openmrs.api.VisitService;
import org.openmrs.api.context.Context;
import org.openmrs.module.atomfeed.transaction.support.AtomFeedSpringTransactionManager;
import org.openmrs.module.fhir.mapper.emr.FHIRMapper;
import org.openmrs.module.fhir.mapper.model.Confidentiality;
import org.openmrs.module.fhir.mapper.model.EntityReference;
import org.openmrs.module.fhir.mapper.model.ShrEncounterBundle;
import org.openmrs.module.fhir.utils.DateUtil;
import org.openmrs.module.fhir.utils.FHIRBundleHelper;
import org.openmrs.module.shrclient.advice.SHREncounterEventService;
import org.openmrs.module.shrclient.dao.IdMappingRepository;
import org.openmrs.module.shrclient.model.EncounterIdMapping;
import org.openmrs.module.shrclient.model.IdMappingType;
import org.openmrs.module.shrclient.service.*;
import org.openmrs.module.shrclient.util.PropertiesReader;
import org.openmrs.module.shrclient.util.SystemProperties;
import org.openmrs.module.shrclient.util.SystemUserService;
import org.openmrs.module.shrclient.web.controller.dto.EncounterEvent;
import org.openmrs.serialization.SerializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Connection;
import java.sql.Savepoint;
import java.util.*;

import static org.openmrs.module.fhir.mapper.model.Confidentiality.getConfidentiality;

@Service("hieEmrEncounterService")
public class EMREncounterServiceImpl implements EMREncounterService {

    private static final Logger logger = Logger.getLogger(EMREncounterServiceImpl.class);
    private EMRPatientService emrPatientService;
    private IdMappingRepository idMappingRepository;
    private PropertiesReader propertiesReader;
    private SystemUserService systemUserService;
    private VisitService visitService;
    private FHIRMapper fhirMapper;
    private OrderService orderService;
    private EMRPatientDeathService patientDeathService;
    private EMRPatientMergeService emrPatientMergeService;
    private VisitLookupService visitLookupService;
    private SHREncounterEventService shrEncounterEventService;
    private EncounterService encounterService;

    @Autowired
    public EMREncounterServiceImpl(@Qualifier("hieEmrPatientService") EMRPatientService emrPatientService, IdMappingRepository idMappingRepository,
                                   PropertiesReader propertiesReader, SystemUserService systemUserService,
                                   VisitService visitService, FHIRMapper fhirMapper, OrderService orderService,
                                   EMRPatientDeathService patientDeathService, EMRPatientMergeService emrPatientMergeService,
                                   VisitLookupService visitLookupService, SHREncounterEventService shrEncounterEventService, EncounterService encounterService) {
        this.emrPatientService = emrPatientService;
        this.idMappingRepository = idMappingRepository;
        this.propertiesReader = propertiesReader;
        this.systemUserService = systemUserService;
        this.visitService = visitService;
        this.fhirMapper = fhirMapper;
        this.orderService = orderService;
        this.patientDeathService = patientDeathService;
        this.emrPatientMergeService = emrPatientMergeService;
        this.visitLookupService = visitLookupService;
        this.shrEncounterEventService = shrEncounterEventService;
        this.encounterService = encounterService;
    }

    @Override
    public void createOrUpdateEncounters(Patient emrPatient, List<EncounterEvent> encounterEvents) throws Exception {
        //Todo: This should be done using transactional
        AFTransactionManager atomFeedTransactionManager = getAtomFeedTransactionManager();
        Connection connection = getConnectionProvider(atomFeedTransactionManager).getConnection();
        Savepoint savepoint = connection.setSavepoint("Before patient encounters download");

        ArrayList<EncounterEvent> failedEncounters = new ArrayList<>();
        for (EncounterEvent encounterEvent : encounterEvents) {
            try {
                createOrUpdateEncounter(emrPatient, encounterEvent);
            } catch (Exception e) {
                failedEncounters.add(encounterEvent);
                connection.rollback(savepoint);
            }
        }

        for (EncounterEvent failedEncounterEvent : failedEncounters) {
            try {
                createOrUpdateEncounter(emrPatient, failedEncounterEvent);
            } catch (Exception e) {
                //TODO do proper handling, write to log API?
                logger.error("error Occurred while trying to process Encounter from SHR.", e);
                throw e;
            }
        }
    }

    @Override
    public void createOrUpdateEncounter(Patient emrPatient, EncounterEvent encounterEvent) throws Exception {
        String shrEncounterId = encounterEvent.getEncounterId();
        String healthId = encounterEvent.getHealthId();
        Bundle bundle = encounterEvent.getBundle();
        logger.debug(String.format("Processing Encounter feed from SHR for patient[%s] with Encounter ID[%s]", healthId, shrEncounterId));
        EncounterIdMapping encounterIdMapping = (EncounterIdMapping) idMappingRepository.findByExternalId(encounterEvent.getEncounterId(), IdMappingType.ENCOUNTER);
        mergeIfHealthIdsDonotMatch(encounterIdMapping, encounterEvent);
        if (!shouldProcessEvent(encounterEvent, encounterIdMapping)) return;
        SystemProperties systemProperties = new SystemProperties(
                propertiesReader.getFrProperties(),
                propertiesReader.getTrProperties(),
                propertiesReader.getPrProperties(),
                propertiesReader.getFacilityInstanceProperties(),
                propertiesReader.getMciProperties(),
                propertiesReader.getShrProperties(),
                propertiesReader.getFhirMappingProperties());

        ShrEncounterBundle shrEncounterBundle = new ShrEncounterBundle(bundle, healthId, shrEncounterId);
        org.openmrs.Encounter newEmrEncounter = fhirMapper.map(emrPatient, shrEncounterBundle, systemProperties);

        VisitType visitType = fhirMapper.getVisitType(shrEncounterBundle,systemProperties);
        Period visitPeriod = fhirMapper.getVisitPeriod(shrEncounterBundle);
        Visit visit = visitLookupService.findOrInitializeVisit(emrPatient, newEmrEncounter.getEncounterDatetime(), visitType, newEmrEncounter.getLocation(), visitPeriod.getStart(), visitPeriod.getEnd());

        visit.addEncounter(newEmrEncounter);
        ArrayList<Order> orders = new ArrayList<>(newEmrEncounter.getOrders());
        for (Order order : orders) {
            newEmrEncounter.removeOrder(order);
        }
        encounterService.saveEncounter(newEmrEncounter);
        for (Order order : orders) {
            newEmrEncounter.addOrder(order);
        }
        visitService.saveVisit(visit);
        //identify location, provider(s), visit
        saveOrders(orders);
        Date encounterUpdatedDate = getEncounterUpdatedDate(encounterEvent);
        addEncounterToIdMapping(newEmrEncounter, shrEncounterId, healthId, systemProperties, encounterUpdatedDate);
        shrEncounterEventService.raiseShrEncounterDownloadEvent(newEmrEncounter);
        systemUserService.setOpenmrsShrSystemUserAsCreator(newEmrEncounter);
        systemUserService.setOpenmrsShrSystemUserAsCreator(newEmrEncounter.getVisit());
        savePatientDeathInfo(emrPatient);
    }

    private void addEncounterToIdMapping(Encounter newEmrEncounter, String shrEncounterId, String healthId, SystemProperties systemProperties, Date encounterUpdatedDate) {
        String internalUuid = newEmrEncounter.getUuid();
        HashMap<String, String> encounterUrlReferenceIds = new HashMap<>();
        encounterUrlReferenceIds.put(EntityReference.HEALTH_ID_REFERENCE, healthId);
        encounterUrlReferenceIds.put(EntityReference.REFERENCE_ID, shrEncounterId);
        String shrEncounterUrl = new EntityReference().build(Encounter.class, systemProperties, encounterUrlReferenceIds);
        EncounterIdMapping encounterIdMapping = new EncounterIdMapping(internalUuid, shrEncounterId, shrEncounterUrl, new Date(), new Date(), encounterUpdatedDate);
        idMappingRepository.saveOrUpdateIdMapping(encounterIdMapping);
    }

    private void saveOrders(ArrayList<Order> orders) {
        List<Order> ordersList = sortOrdersOnDateActivated(orders);
        for (Order order : ordersList) {
            if (isNewOrder(order)) {
                orderService.saveRetrospectiveOrder(order, null);
            }
        }
    }

    private List<Order> sortOrdersOnDateActivated(ArrayList<Order> orders) {
        List<Order> ordersList = new ArrayList<>(orders);
        ordersList.sort(Comparator.comparing(Order::getDateActivated));
        return ordersList;
    }

    private boolean isNewOrder(Order order) {
        return order.getOrderId() == null;
    }

    private boolean shouldProcessEvent(EncounterEvent encounterEvent, EncounterIdMapping encounterIdMapping) {
        if (hasUpdatedEncounterInTheFeed(encounterEvent)) return false;
        Date encounterUpdatedDate = getEncounterUpdatedDate(encounterEvent);
        if (isUpdateAlreadyProcessed(encounterIdMapping, encounterUpdatedDate)) return false;
        return getEncounterConfidentiality(encounterEvent.getBundle()).ordinal() <= Confidentiality.Normal.ordinal();
    }

    private void mergeIfHealthIdsDonotMatch(EncounterIdMapping encounterIdMapping, EncounterEvent encounterEvent) {
        if (encounterIdMapping != null && !encounterIdMapping.getHealthId().equals(encounterEvent.getHealthId())) {
            try {
                emrPatientMergeService.mergePatients(encounterEvent.getHealthId(), encounterIdMapping.getHealthId());
            } catch (SerializationException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private boolean isUpdateAlreadyProcessed(EncounterIdMapping encounterIdMapping, Date encounterUpdatedDate) {
        if (encounterIdMapping == null) return false;
        Date serverUpdateDateTime = encounterIdMapping.getServerUpdateDateTime();
        if (serverUpdateDateTime == null) return true;
        return encounterUpdatedDate.before(serverUpdateDateTime) || encounterUpdatedDate.equals(serverUpdateDateTime);
    }

    private Date getEncounterUpdatedDate(EncounterEvent encounterEvent) {
        Category encounterUpdatedCategory = encounterEvent.getEncounterUpdatedCategory();
        String encounterUpdatedDate = StringUtils.substringAfter(encounterUpdatedCategory.getTerm(), ":");
        return DateUtil.parseDate(encounterUpdatedDate);
    }

    private boolean hasUpdatedEncounterInTheFeed(EncounterEvent encounterEvent) {
        return encounterEvent.getLatestUpdateEventCategory() != null;
    }

    private Confidentiality getEncounterConfidentiality(Bundle bundle) {
        Composition composition = FHIRBundleHelper.getComposition(bundle);
        Composition.DocumentConfidentiality confidentiality = composition.getConfidentiality();
        if (null == confidentiality) {
            return Confidentiality.Normal;
        }
        return getConfidentiality(confidentiality.toCode());
    }

    private void savePatientDeathInfo(org.openmrs.Patient emrPatient) {
        if (emrPatient.getDead()) {
            emrPatient.setCauseOfDeath(patientDeathService.getCauseOfDeath(emrPatient));
            emrPatientService.savePatient(emrPatient);
            systemUserService.setOpenmrsShrSystemUserAsCreator(emrPatient);
        }
    }

    private JdbcConnectionProvider getConnectionProvider(AFTransactionManager txMgr) {
        if (txMgr instanceof AtomFeedSpringTransactionManager) {
            return (AtomFeedSpringTransactionManager) txMgr;
        }
        throw new RuntimeException("Atom Feed TransactionManager should provide a connection provider.");
    }

    private AFTransactionManager getAtomFeedTransactionManager() {
        return new AtomFeedSpringTransactionManager(getSpringPlatformTransactionManager());
    }

    private PlatformTransactionManager getSpringPlatformTransactionManager() {
        List<PlatformTransactionManager> platformTransactionManagers = Context.getRegisteredComponents
                (PlatformTransactionManager.class);
        return platformTransactionManagers.get(0);
    }
}
