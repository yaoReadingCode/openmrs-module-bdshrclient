package org.openmrs.module.fhir.mapper.emr;

import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.Type;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.module.fhir.mapper.model.EmrEncounter;
import org.openmrs.module.fhir.mapper.model.ShrEncounterBundle;
import org.openmrs.module.fhir.utils.FHIREncounterUtil;
import org.openmrs.module.fhir.utils.OMRSConceptLookup;
import org.openmrs.module.shrclient.util.SystemProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.ParseException;

import static org.openmrs.module.fhir.MRSProperties.LOCAL_CONCEPT_VERSION_PREFIX;
import static org.openmrs.module.fhir.OpenMRSConstants.MISC_CONCEPT_CLASS_NAME;
import static org.openmrs.module.fhir.OpenMRSConstants.TEXT_CONCEPT_DATATYPE_NAME;
import static org.openmrs.module.fhir.utils.FHIRBundleHelper.findResourceByReference;

@Component
public class FHIRObservationsMapper implements FHIRResourceMapper {
    private final OMRSConceptLookup omrsConceptLookup;
    private final FHIRObservationValueMapper resourceValueMapper;

    @Autowired
    public FHIRObservationsMapper(OMRSConceptLookup omrsConceptLookup, FHIRObservationValueMapper resourceValueMapper) {
        this.omrsConceptLookup = omrsConceptLookup;
        this.resourceValueMapper = resourceValueMapper;
    }

    @Override
    public boolean canHandle(Resource resource) {
        return (resource instanceof Observation);
    }

    @Override
    public void map(Resource resource, EmrEncounter emrEncounter, ShrEncounterBundle shrEncounterBundle, SystemProperties systemProperties) {
        Observation observation = (Observation) resource;
        Obs obs = mapObs(shrEncounterBundle, emrEncounter, observation);
        emrEncounter.addObs(obs);
    }

    public Obs mapObs(ShrEncounterBundle shrEncounterBundle, EmrEncounter emrEncounter, Observation observation) {
        String facilityId = FHIREncounterUtil.getFacilityId(shrEncounterBundle.getBundle());
        Concept concept = mapConcept(observation, facilityId);
        if (concept == null) return null;
        Obs result = new Obs();
        result.setConcept(concept);
        try {
            mapValue(observation, concept, result);
            mapRelatedObservations(shrEncounterBundle, observation, result, emrEncounter);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return result;
    }

    private void mapValue(Observation observation, Concept concept, Obs result) throws ParseException {
        if (isLocallyCreatedConcept(concept)) {
            mapValueAsString(observation, result);
        } else {
            Type value = observation.getValue();
            resourceValueMapper.map(value, result);
        }
    }

    private void mapRelatedObservations(ShrEncounterBundle encounterComposition, Observation observation, Obs obs, EmrEncounter emrEncounter) throws ParseException {
        for (Observation.ObservationRelatedComponent component : observation.getRelated()) {
            Obs member;
            Observation relatedObs = (Observation) findResourceByReference(encounterComposition.getBundle(), component.getTarget());
            member = mapObs(encounterComposition, emrEncounter, relatedObs);
            if (member != null) {
                obs.addGroupMember(member);
            }
        }
    }

    private boolean isLocallyCreatedConcept(Concept concept) {
        return concept.getVersion() != null && concept.getVersion().startsWith(LOCAL_CONCEPT_VERSION_PREFIX);
    }

    private void mapValueAsString(Observation relatedObs, Obs result) throws ParseException {
        Type value = relatedObs.getValue();
        if (value != null)
            result.setValueAsString(ObservationValueConverter.convertToText(value));
    }

    private Concept mapConcept(Observation observation, String facilityId) {
        CodeableConcept observationName = observation.getCode();
        if (observationName.getCoding() != null && observationName.getCoding().isEmpty()) {
            return null;
        }
        return omrsConceptLookup.findOrCreateLocalConceptByCodings(observationName.getCoding(), facilityId, MISC_CONCEPT_CLASS_NAME, TEXT_CONCEPT_DATATYPE_NAME);
    }
}
