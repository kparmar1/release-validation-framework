package org.ihtsdo.rvf.execution.service.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.ihtsdo.drools.response.InvalidContent;
import org.ihtsdo.drools.response.Severity;
import org.ihtsdo.drools.validator.rf2.DroolsRF2Validator;
import org.ihtsdo.otf.resourcemanager.ManualResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.ihtsdo.otf.snomedboot.ReleaseImportException;
import org.ihtsdo.otf.snomedboot.ReleaseImporter;
import org.ihtsdo.rvf.entity.DroolsRulesValidationReport;
import org.ihtsdo.rvf.entity.FailureDetail;
import org.ihtsdo.rvf.entity.TestRunItem;
import org.ihtsdo.rvf.entity.TestType;
import org.ihtsdo.rvf.entity.ValidationReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.aws.core.io.s3.SimpleStorageResourceLoader;
import org.springframework.stereotype.Service;

import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.collect.Sets;

@Service
public class DroolsRulesValidationService {
	
	@Value("${rvf.drools.rule.directory}")
	private String droolsRuleDirectoryPath;
	
	@Value("${test-resources.cloud.bucket}")
	private String testResourceBucket;

	@Value("${test-resources.cloud.path}")
	private String testResourcePath;
	
	@Autowired
	private ValidationVersionLoader releaseVersionLoader;

	public void runDroolsAssertions(Map<String, Object> responseMap, ValidationReport validationReport, ValidationRunConfig validationConfig, ExecutionConfig executionConfig) throws RVFExecutionException {
		long timeStart = new Date().getTime();
		//Filter only Drools rules set from all the assertion groups
		Set<String> droolsRulesSets = getDroolsRulesSetFromAssertionGroups(Sets.newHashSet(validationConfig.getDroolsRulesGroupList()));
		Set<String> directoryPaths = new HashSet<>();
		//Skip running Drools rules set altogether if there is no Drools rules set in the assertion groups
		if(droolsRulesSets.isEmpty()) return;
		try {
			List<InvalidContent> invalidContents;
			try {
				Set<InputStream> snapshotsInputStream = new HashSet<>();
				InputStream deltaInputStream = null;

				InputStream testedReleaseFileStream = new FileInputStream(validationConfig.getLocalProspectiveFile());
				//If the validation is Delta validation, previous snapshot file must be loaded to snapshot files list.
				if(validationConfig.isRf2DeltaOnly()) {
					releaseVersionLoader.downloadPreviousVersion(validationConfig);
					InputStream previousStream = new FileInputStream(validationConfig.getLocalPreviousFile());
					snapshotsInputStream.add(previousStream);
					deltaInputStream = testedReleaseFileStream;
				} else {
					//If the validation is Snapshot validation, current file must be loaded to snapshot files list
					snapshotsInputStream.add(testedReleaseFileStream);
				}

				//Load the dependency package from S3 to snapshot files list before validating if the package is a MS extension and not an edition release
				//If the package is an MS edition, it is not necessary to load the dependency
				Set<String> modulesSet = null;
				if(executionConfig.isExtensionValidation() && !validationConfig.isReleaseAsAnEdition()) {
					releaseVersionLoader.downloadDependencyVersion(validationConfig);
					InputStream dependencyStream = new FileInputStream(validationConfig.getLocalDependencyFile());
					snapshotsInputStream.add(dependencyStream);

					//Will filter the results based on component's module IDs if the package is an extension only
					String moduleIds = validationConfig.getIncludedModules();
					if(StringUtils.isNotBlank(moduleIds)) {
						modulesSet = Sets.newHashSet(moduleIds.split(","));
					}
				}

				ResourceConfiguration manualResourceConfiguration = new ManualResourceConfiguration(true,true,null,new ResourceConfiguration.Cloud(testResourceBucket,testResourcePath));
				ResourceManager resourceManager = new ResourceManager(manualResourceConfiguration, new SimpleStorageResourceLoader(new AmazonS3Client(new AnonymousAWSCredentials())));
				DroolsRF2Validator droolsRF2Validator = new DroolsRF2Validator(droolsRuleDirectoryPath, resourceManager);
				String effectiveTime = validationConfig.getEffectiveTime();
				if (StringUtils.isNotBlank(effectiveTime)) {
					effectiveTime = effectiveTime.replaceAll("-", "");
				} else {
					effectiveTime = "";
				}
				for (InputStream inputStream : snapshotsInputStream) {
					String snapshotDirectoryPath = new ReleaseImporter().unzipRelease(inputStream, ReleaseImporter.ImportType.SNAPSHOT).getAbsolutePath();
					directoryPaths.add(snapshotDirectoryPath);
				}
				String deltaDirectoryPath = null;
				if(deltaInputStream != null) {
					deltaDirectoryPath = new ReleaseImporter().unzipRelease(deltaInputStream, ReleaseImporter.ImportType.DELTA).getAbsolutePath();
				}

				invalidContents = droolsRF2Validator.validateSnapshots(directoryPaths, deltaDirectoryPath, droolsRulesSets, effectiveTime, modulesSet);
			} catch (ReleaseImportException | IOException e) {
				throw new RVFExecutionException("Failed to load RF2 snapshot for Drools validation.", e);
			}
			HashMap<String, List<InvalidContent>> invalidContentMap = new HashMap<>();
			for (InvalidContent invalidContent : invalidContents) {
				if (!invalidContentMap.containsKey(invalidContent.getMessage())) {
					List<InvalidContent> invalidContentArrayList = new ArrayList<>();
					invalidContentArrayList.add(invalidContent);
					invalidContentMap.put(invalidContent.getMessage(), invalidContentArrayList);
				} else {
					invalidContentMap.get(invalidContent.getMessage()).add(invalidContent);
				}
			}
			invalidContents.clear();
			List<TestRunItem> failedAssertions = new ArrayList<>();
			List<TestRunItem> warningAssertions = new ArrayList<>();
			int failureExportMax = validationConfig.getFailureExportMax() != null ? validationConfig.getFailureExportMax() : 10;
			Map<String, List<InvalidContent>> groupRules = new HashMap<>();
			for (String rule : invalidContentMap.keySet()) {
				TestRunItem validationRule = new TestRunItem();
				validationRule.setTestType(TestType.DROOL_RULES);
				validationRule.setTestCategory("");
				//Some Drools validations message has SCTID, making it is impossible to group the same failures together unless the message is generalized by replacing the SCTID
				String groupedRuleName = rule.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}","<UUID>")
						.replaceAll("\\d{6,20}","<SCTID>");
				if(groupedRuleName.contains("<UUID>") || groupedRuleName.contains("<SCTID>")) {
					groupDroolsRules(groupRules, groupedRuleName, invalidContentMap.get(rule), failureExportMax);
				} else {
					validationRule.setAssertionText(rule);
					List<InvalidContent> invalidContentList = invalidContentMap.get(rule);
					validationRule.setFailureCount((long) invalidContentList.size());
					validationRule.setFirstNInstances(invalidContentList.stream().limit(failureExportMax)
							.map(item -> new FailureDetail(item.getConceptId(), item.getMessage()))
							.collect(Collectors.toList()));
					Severity severity = invalidContentList.get(0).getSeverity();
					if(Severity.WARNING.equals(severity)) {
						warningAssertions.add(validationRule);
					} else {
						failedAssertions.add(validationRule);
					}
				}
			}
			if(!groupRules.isEmpty()) {
				for (String rule : groupRules.keySet()) {
					TestRunItem testRunItem = new TestRunItem();
					testRunItem.setTestType(TestType.DROOL_RULES);
					testRunItem.setTestCategory("");
					testRunItem.setAssertionText(rule);
					List<InvalidContent> invalidContentList = groupRules.get(rule);
					testRunItem.setFailureCount((long)invalidContentList.size());
					testRunItem.setFirstNInstances(invalidContentList.stream().limit(failureExportMax)
							.map(item -> new FailureDetail(item.getConceptId(), item.getMessage()))
							.collect(Collectors.toList()));
					Severity severity = invalidContentList.get(0).getSeverity();
					if(Severity.WARNING.equals(severity)) {
						warningAssertions.add(testRunItem);
					} else {
						failedAssertions.add(testRunItem);
					}
				}
			}
			validationReport.addFailedAssertions(failedAssertions);
			validationReport.addWarningAssertions(warningAssertions);
			validationReport.addTimeTaken((System.currentTimeMillis() - timeStart) / 1000);
		} catch (Exception ex) {
			final DroolsRulesValidationReport report = new DroolsRulesValidationReport(TestType.DROOL_RULES);
			report.setRuleSetExecuted(String.join(",", droolsRulesSets));
			report.setTimeTakenInSeconds((System.currentTimeMillis() - timeStart) / 1000);
			report.setExecutionId(executionConfig.getExecutionId());
			report.setMessage(ExceptionUtils.getStackTrace(ex));
			report.setCompleted(false);
			responseMap.put(report.getTestType().toString() + "TestResult", report);
		} finally {
			for (String directoryPath : directoryPaths) {
				FileUtils.deleteQuietly(new File(directoryPath));
			}
		}
	}
	

	private Set<String> getDroolsRulesSetFromAssertionGroups(Set<String> assertionGroups) throws RVFExecutionException {
		File droolsRuleDir = new File(droolsRuleDirectoryPath);
		if(!droolsRuleDir.isDirectory()) throw new RVFExecutionException("Drools rules directory path " + droolsRuleDirectoryPath + " is not a directory or inaccessible");
		Set<String> droolsRulesModules = new HashSet<>();
		File[] droolsRulesSubfiles = droolsRuleDir.listFiles();
		for (File droolsRulesSubfile : droolsRulesSubfiles) {
			if(droolsRulesSubfile.isDirectory()) droolsRulesModules.add(droolsRulesSubfile.getName());
		}
		//Only keep the assertion groups with matching Drools Rule modules in the Drools Directory
		droolsRulesModules.retainAll(assertionGroups);
		return droolsRulesModules;
	}

	private Map<String, List<InvalidContent>> groupDroolsRules(Map<String, List<InvalidContent>> groupedRules, String rule, List<InvalidContent> invalidContents,
															   int failureMaxExport) {
		if(!groupedRules.containsKey(rule)) {
			groupedRules.put(rule, new ArrayList<>());
		}
		groupedRules.get(rule).addAll(invalidContents);
		return groupedRules;
	}
}
