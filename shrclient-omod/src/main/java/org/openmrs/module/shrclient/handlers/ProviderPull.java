package org.openmrs.module.shrclient.handlers;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.module.fhir.utils.DateUtil;
import org.openmrs.module.shrclient.mapper.ProviderMapper;
import org.openmrs.module.shrclient.model.ProviderEntry;
import org.openmrs.module.shrclient.util.PropertiesReader;
import org.openmrs.module.shrclient.util.RestClient;
import org.openmrs.module.shrclient.util.ScheduledTaskHistory;
import org.openmrs.module.shrclient.util.StringUtil;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.openmrs.module.shrclient.util.URLParser.parseURL;

public class ProviderPull {
    private static final int MAX_NUMBER_OF_ENTRIES_TO_BE_SYNCHRONIZED = 1000;
    public static final String PR_FEED_URI = "urn://pr/providers";
    public static final String PR_PROVIDERS_PATH_INFO = "pr.pathInfo";
    private static final String OFFSET = "offset";
    private static final String UPDATED_SINCE = "updatedSince";
    private static final int DEFAULT_LIMIT = 100;
    private static final int INITIAL_OFFSET = 0;
    private static final String INITIAL_DATETIME = "0000-00-00 00:00:00";
    private static final String EXTRA_FILTER_PATTERN = "?offset=%d&limit=%d&updatedSince=%s";
    private static final String ENCODED_SINGLE_SPACE = "%20";
    private static final String SINGLE_SPACE = " ";

    private final PropertiesReader propertiesReader;
    private final RestClient prClient;
    private ScheduledTaskHistory scheduledTaskHistory;
    private ProviderMapper providerMapper;

    private final org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(ProviderPull.class);

    public ProviderPull(PropertiesReader propertiesReader,
                        RestClient prClient,
                        ScheduledTaskHistory scheduledTaskHistory,
                        ProviderMapper providerMapper) {
        this.propertiesReader = propertiesReader;
        this.prClient = prClient;
        this.scheduledTaskHistory = scheduledTaskHistory;
        this.providerMapper = providerMapper;
    }

    public void synchronize() throws IOException {
        synchronizeUpdates();
    }

    private void synchronizeUpdates() throws IOException {
        int offset = INITIAL_OFFSET;
        String updatedSince = INITIAL_DATETIME;
        String feedUriForLastReadEntry = scheduledTaskHistory.getFeedUriForLastReadEntryByFeedUri(PR_FEED_URI);
        if (StringUtils.isNotBlank(feedUriForLastReadEntry)) {
            Map<String, String> parameters = parseURL(new URL(feedUriForLastReadEntry));
            offset = Integer.parseInt(parameters.get(OFFSET));
            updatedSince = parameters.get(UPDATED_SINCE);
        }

        String baseContextPath = propertiesReader.getPrProperties().getProperty(PR_PROVIDERS_PATH_INFO);
        int noOfEntriesSynchronizedSoFar = 0;
        List<ProviderEntry> newEntriesFromPr;
        String completeContextPath;
        do {
            completeContextPath = buildCompleteContextPath(baseContextPath, offset, updatedSince);
            newEntriesFromPr = getNextChunkOfUpdatesFromPr(completeContextPath);
            if (newEntriesFromPr != null) {
                saveOrUpdateProviderEntries(newEntriesFromPr);
                offset += newEntriesFromPr.size();
                noOfEntriesSynchronizedSoFar += newEntriesFromPr.size();
            }
        }
        while (newEntriesFromPr != null && newEntriesFromPr.size() == DEFAULT_LIMIT && noOfEntriesSynchronizedSoFar < MAX_NUMBER_OF_ENTRIES_TO_BE_SYNCHRONIZED);

        updateMarkers(noOfEntriesSynchronizedSoFar, offset, newEntriesFromPr, baseContextPath);

        logger.info(noOfEntriesSynchronizedSoFar + " entries synchronized");
    }

    private void updateMarkers(int noOfEntriesSynchronizedSoFar, int offset, List<ProviderEntry> newEntriesFromPr, String baseContextPath) {
        String nextCompleteContextPath;
        String providerResourceRefPath = StringUtil.ensureSuffix(propertiesReader.getPrBaseUrl(), "/");
        if (newEntriesFromPr != null) {
            if (newEntriesFromPr.size() == DEFAULT_LIMIT) {
                nextCompleteContextPath = buildCompleteContextPath(baseContextPath, offset, INITIAL_DATETIME);
                scheduledTaskHistory.setFeedUriForLastReadEntryByFeedUri(providerResourceRefPath + StringUtil.removePrefix(nextCompleteContextPath, "/"), PR_FEED_URI);
            } else {
                nextCompleteContextPath = buildCompleteContextPath(baseContextPath, INITIAL_OFFSET,
                        DateUtil.toDateString(new Date(), DateUtil.SIMPLE_DATE_WITH_SECS_FORMAT));
                scheduledTaskHistory.setFeedUriForLastReadEntryByFeedUri(providerResourceRefPath + StringUtil.removePrefix(nextCompleteContextPath, "/"), PR_FEED_URI);
            }

            if (noOfEntriesSynchronizedSoFar != 0) {
                ProviderEntry providerEntry = newEntriesFromPr.get(newEntriesFromPr.size() - 1);
                scheduledTaskHistory.setLastReadEntryId(providerEntry.getId(), PR_FEED_URI);
            }
        }
    }

    private void saveOrUpdateProviderEntries(List<ProviderEntry> providerEntries) {
        for (ProviderEntry providerEntry : providerEntries) {
            try {
                providerMapper.createOrUpdate(providerEntry);
            } catch (Exception e) {
                logger.error(String.format("Unable to save or update provider with id[%s]",providerEntry.getId()), e);
            }
        }
    }

    private List<ProviderEntry> getNextChunkOfUpdatesFromPr(String completeContextPath) {
        List<ProviderEntry> downloadedData = null;
        try {
            ProviderEntry[] providerEntries = prClient.get(completeContextPath, ProviderEntry[].class);
            if (providerEntries == null) {
                logger.info("No updates from PR");
            } else {
                downloadedData = Arrays.asList(providerEntries);
            }
        } catch (Exception e) {
            logger.error("Error while downloading updates from PR : " + e);
        }
        return downloadedData;
    }

    private String buildCompleteContextPath(String baseContextPath, int offset, String updatedSince) {
        return baseContextPath + getExtraFilters(offset, updatedSince);
    }

    private String getExtraFilters(int offset, String updatedSince) {
        return String.format(EXTRA_FILTER_PATTERN, offset, DEFAULT_LIMIT, updatedSince)
                .replace(SINGLE_SPACE, ENCODED_SINGLE_SPACE);
    }
}
