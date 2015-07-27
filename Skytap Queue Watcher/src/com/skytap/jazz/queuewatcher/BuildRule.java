package com.skytap.jazz.queuewatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class BuildRule {
	private String[] buildIds;
	private String templateId;
	private int buildThreshold;
	private int maxConfigCount;
	private ArrayList<String> configIds;
	private HashMap<String, HashMap<String,ArrayList<String>>> buildEngines;
	
	public static BuildRule[] GenerateBuildRules(String buildRuleConfig) {
		ArrayList<BuildRule> buildRules = new ArrayList<BuildRule>();
		String[] firstCut = buildRuleConfig.split(";");
		for(String unsplitRule : firstCut) {
			String[] secondCut = unsplitRule.split("\\|");
			String[] thirdCut = secondCut[0].split(",");
			int maxConfigs = 0;
			if (secondCut.length == 4)
				Integer.parseInt(secondCut[3]);
			
			BuildRule buildRule = new BuildRule(thirdCut, Integer.parseInt(secondCut[1]), secondCut[2], maxConfigs);
			buildRules.add(buildRule);
		}
		
		return buildRules.toArray(new BuildRule[buildRules.size()]);
	}
	
	public static int TotalNumberOfActiveConfigs(BuildRule[] rules) {
		int count = 0;
		for(BuildRule rule : rules)
			count += rule.getCurrentConfigCount();
		
		return count;
	}
	
	public BuildRule(String[] buildIds, int buildThreshold, String templateId, int maxConfigCount) {
		this.buildIds = buildIds;
		this.buildThreshold = buildThreshold;
		this.templateId = templateId;
		this.maxConfigCount = maxConfigCount;
		this.configIds = new ArrayList<String>();
		this.buildEngines = new HashMap<String, HashMap<String,ArrayList<String>>>();
	}
	
	public String[] getBuildIds() {
		return buildIds;
	}
	public int getBuildThreshold() {
		return buildThreshold;
	}
	public String getTemplateId() {
		return templateId;
	}
	public int getMaxConfigCount() {
		return maxConfigCount;
	}
	public int getCurrentConfigCount() {
		return this.configIds.size();
	}
	public String[] getConfigIds() {
		return this.configIds.toArray(new String[this.configIds.size()]);
	}
	public String getConfigId(int index) {
		return this.getConfigId(index);
	}
	public void removeConfigId(String configId) {
		this.configIds.remove(configId);
	}
	public void addConfigId(String configId) {
		this.configIds.add(configId);
	}
	public boolean containsConfigId(String configId) {
		return this.configIds.contains(configId);
	}
	public Iterator<String> iteratorConfigId() {
		return this.configIds.iterator();
	}
	
	public HashMap<String, HashMap<String,ArrayList<String>>> getBuildEngines() {
		return this.buildEngines;
	}
	
	public void putBuildEngine(String configId, String queueName, ArrayList<String> buildEngines) {
		HashMap<String, ArrayList<String>> item = new HashMap<String, ArrayList<String>>();
		item.put(queueName, buildEngines);
		this.buildEngines.put(configId, item);
	}
	
	public String[] getBuildEnginesQueuesForConfig(String configId) {
		ArrayList<String> queues = new ArrayList<String>();
		
		Iterator<String> iterator = this.buildEngines.get(configId).keySet().iterator();
		while (iterator.hasNext()) {
			String key = iterator.next();
			queues.add(key);
		}
		
		return queues.toArray(new String[queues.size()]);
	}
	
	public ArrayList<String> getBuildEngines(String configId, String queueName) {
		return this.buildEngines.get(configId).get(queueName);
	}
}
