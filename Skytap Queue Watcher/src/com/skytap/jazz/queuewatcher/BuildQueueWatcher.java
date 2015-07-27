/**
 * Copyright 2014 Skytap Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package com.skytap.jazz.queuewatcher;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.Properties;
import java.util.logging.Level;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ibm.team.build.client.ClientFactory;
import com.ibm.team.build.client.ITeamBuildClient;
import com.ibm.team.build.common.model.BuildState;
import com.ibm.team.build.common.model.IBuildDefinition;
import com.ibm.team.build.common.model.IBuildEngine;
import com.ibm.team.build.common.model.IBuildEngineHandle;
import com.ibm.team.build.common.model.query.IBaseBuildEngineQueryModel.IBuildEngineQueryModel;
import com.ibm.team.build.common.model.query.IBaseBuildResultQueryModel.IBuildResultQueryModel;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.client.TeamPlatform;
import com.ibm.team.repository.common.IItem;
import com.ibm.team.repository.common.IItemHandle;
import com.ibm.team.repository.common.IItemType;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.common.query.IItemQuery;
import com.ibm.team.repository.common.query.IItemQueryPage;
import com.ibm.team.repository.common.query.ast.IPredicate;
import com.ibm.team.repository.common.service.IQueryService;
import com.ibm.team.repository.common.util.ObfuscationHelper;
import com.skytap.api.Skytap;

public class BuildQueueWatcher {

	private String queueWatcherVersionId = "1.0b";
	private long sleepTime = 300;
	private String repositoryURL = null;
	private String userId = null;
	private String password = null;
	private String passwordFile = null;
	private String skytapuser = null;
	private String skytapapikey = null;
	private String loglevel = "info";
	private String nettype = null;
	private String netid = null;
	private int skytapmaxconfigs = 0;
	private BuildRule[] buildRules = null;
	private boolean loop = true;
	
	private String jtsNetId;
	private String buildEngineNetId;

	private static final NullProgressMonitor NULL_PROGRESS_MONITOR = new NullProgressMonitor();
	private static BuildQueueWatcher bwq = new BuildQueueWatcher();

	public static void main(String[] args) throws IOException {
		if (args.length == 1)
		{
			if (args[0].equalsIgnoreCase("start")) {
				bwq.start();
			} else {
				bwq.stop();
			}
		} else {
			bwq.start(args);
		}
	}
	
	public void init(String[] args) {
		// Do nothing as we don't need too for jsvc
	}
	
	public void destroy() {
		// DO nothing as we don't need too for jsvc
	}
	
	public void start() throws IOException {
		bwq.start(new String[] {});
	}
	
	public void stop() {
		loop = false;
		synchronized(this) {
			this.notify();
		}
	}

	public void start(String[] args) {
		processArguments(args);
		int subNetNum;
		String buildEngineSubnet;
		String[] subNetId;
		subNetId = new String[254];


		if (checkArgs()) {
			if (passwordFile != null && passwordFile.trim().length() > 0) {
				password = getDecryptedPassword(passwordFile);
			}
			System.out.println("Skytap Queue Watcher - Version " + queueWatcherVersionId);
			TeamPlatform.startup();
			ITeamRepository repo = null;
			try {
				repo = login(NULL_PROGRESS_MONITOR);
			} catch (TeamRepositoryException e) {
				System.err.println("ERROR: Unable to log into the repository.\n Exception message: " + e.getMessage());
			}
			
			if (repo != null) {
				/*
				try{ 
					ArrayList<String> output = getBuildEnginesByDefinitionAndName(repo, "junit", "%");
					String listString = output.toString();
					System.out.println("ListString: " + listString);
					String better = listString.substring(1, listString.length()-1);//.split(",\\s*");
					System.out.println("Output: " + better);
					loop = false;
				} catch (Exception e) { loop = false; }*/
				while(loop) {
					try {
						for(BuildRule rule : buildRules) {
							for(String queueName : rule.getBuildIds()) {
								if (loglevel.equalsIgnoreCase("verbose")) {
									System.out.println("Checking for pending builds in queue " + queueName + "...");
								}
								int numBuilds = buildsInQueue(repo, queueName);
								if (loglevel.equalsIgnoreCase("verbose")) {
									System.out.println("Number of pending builds for queue " + queueName + " is " + numBuilds);
								}
								if ((numBuilds - rule.getCurrentConfigCount()) >= rule.getBuildThreshold()) {
									if (skytapmaxconfigs == 0 || BuildRule.TotalNumberOfActiveConfigs(buildRules) < skytapmaxconfigs) {
										if (loglevel.equalsIgnoreCase("verbose")) {
											System.out.println("Creating a Skytap Configuration based on template " + rule.getTemplateId());
										}
										String configId = Skytap.CreateConfiguration(rule.getTemplateId());
										if (!Skytap.isNullOrEmpty(configId)) {
											Skytap.WaitForConfigToReturnToState(configId, "stopped");
											ArrayList<String> beforeBuildEngines = getBuildEnginesByDefinitionAndName(repo, queueName, "skytap%");
											if (loglevel.equalsIgnoreCase("verbose")) {
												System.out.println("Configuring Network Type: " + nettype.toUpperCase());
											}

											// If nettype=icnr create an icnr connection between the JTS network and the newly instantiated config
											if (nettype.equalsIgnoreCase("icnr")) {
												// Find an open subnet
													subNetNum = 0;
													for (int subnetix = 0; subnetix < 255; subnetix = subnetix + 1) {
														if (subNetId[subnetix] == null) {
															subNetId[subnetix] = configId;
															subNetNum = subnetix + 1;
															break;
														}
													}
													
													jtsNetId = Skytap.GetNetworkIDInConfiguration(netid);
													buildEngineNetId = Skytap.GetNetworkIDInConfiguration(configId);
													buildEngineSubnet = Skytap.GetSubnetInConfigNetwork(configId, buildEngineNetId);
													String[] subnetParts = buildEngineSubnet.split("\\.");
													if (loglevel.equalsIgnoreCase("verbose")) {
														System.out.println("Assigning Build Engine Subnet: " + subnetParts[0] + "." + subnetParts[1] + "." + subNetNum + "." + subnetParts[3]);
													}
													Skytap.UpdateConfigSubnet(configId, subnetParts[0] + "." + subnetParts[1] + "." + subNetNum + "." + subnetParts[3]);
													Skytap.CreateICNRConnection(jtsNetId, buildEngineNetId);
											} else {
												if (nettype.equalsIgnoreCase("vpn")) {
													// Find an open subnet
													subNetNum = 0;
													for (int subnetix = 0; subnetix < 255; subnetix = subnetix + 1) {
														if (subNetId[subnetix] == null) {
															subNetId[subnetix] = configId;
															subNetNum = subnetix + 1;
															break;
														}
													}
													
													buildEngineNetId = Skytap.GetNetworkIDInConfiguration(configId);
													buildEngineSubnet = Skytap.GetSubnetInConfigNetwork(configId, buildEngineNetId);
													String[] subnetParts = buildEngineSubnet.split("\\.");
													if (loglevel.equalsIgnoreCase("verbose")) {
														System.out.println("Assigning Build Engine Subnet: " + subnetParts[0] + "." + subnetParts[1] + "." + subNetNum + "." + subnetParts[3]);
													}
													Skytap.UpdateConfigSubnet(configId, subnetParts[0] + "." + subnetParts[1] + "." + subNetNum + "." + subnetParts[3]);
//													System.out.println("DEBUG: Configuring VPN.  configId: " + configId + "Build Engine Net Id: " + buildEngineNetId + "VPN Net ID: "+ netid);
//													buildEngineNetId = Skytap.GetNetworkIDInConfiguration(configId);
//													Skytap.AttachVPNConnection(configId, buildEngineNetId, netid);
													Skytap.WaitForConfigToReturnToState(configId, "stopped");
//													System.out.println("DEBUG: ConnectVPN:  configId: " + configId + "Build Engine Net Id: " + buildEngineNetId + "VPN Net ID: "+ netid);
													Skytap.ConnectVPN(configId, buildEngineNetId, netid);
													if (loglevel.equalsIgnoreCase("verbose")) {
														System.out.println("VPN Connected");
													}
												}
											}
											if (loglevel.equalsIgnoreCase("verbose")) {
												System.out.println("Network Configured, sleeping for 10 seconds...");
											}
											try {
												Thread.sleep(10 * 1000);
											} catch (InterruptedException e) {
												System.err.println("ERROR: error sleeping...");
											}
											
												
											if (loglevel.equalsIgnoreCase("verbose")) {
												System.out.println("Starting up Skytap Configuration " + configId + ", Network Type: " + nettype.toUpperCase());
											}
											for (int tryIx=0; tryIx < 12; tryIx = tryIx + 1) {
												String runstate = Skytap.GetConfigurationState(configId);
												if (runstate.equalsIgnoreCase("running"))
									            {
									                break;
									            } else {
									            	if (! runstate.equalsIgnoreCase("busy")) {
														Skytap.RunConfiguration(configId);
									            	} else {
													   try {
														   Thread.sleep(10 * 1000);
													   } catch (InterruptedException e) {
														   System.err.println("ERROR: interruption during run configuration retry.");
													   }
									            	}
									            }
											}
											
											if (Skytap.WaitForConfigToReturnToState(configId, "running")){
												rule.addConfigId(configId);
												try {
													if (loglevel.equalsIgnoreCase("verbose")) {
														System.out.println("Waiting for Configuration " + configId + " to register with Jazz...");
													}
													ArrayList<String> afterBuildEngines = null;
													do {
//														System.out.println("DEBUG: Polling Build Engines...");
														afterBuildEngines = getBuildEnginesByDefinitionAndName(repo, queueName, "skytap%");
														afterBuildEngines.removeAll(beforeBuildEngines);
//														System.out.println("DEBUG: " + afterBuildEngines.size() + " Build Engines found");
														Thread.sleep(5 * 1000);
													} while (afterBuildEngines.size() < 1);
													rule.putBuildEngine(configId, queueName, afterBuildEngines);
												} catch (Exception ex) {
													System.err.println("ERROR: error waiting for Jazz registration");
												}
											} else {
												Skytap.DeleteConfiguration(configId);
											}
										}
										
										// already spun up a config for this "set" of queues, skip the rest
										continue;
									} else {
										if (loglevel.equalsIgnoreCase("verbose")) {
											System.out.println("Already reached max number of configs for this rule");
										}
									}
								}
							}
							for(String configId : rule.getConfigIds()) {
								String configState = Skytap.GetConfigurationState(configId);
								ArrayList<String> configsQueues = new ArrayList<String>();
								for(String queueName : rule.getBuildEnginesQueuesForConfig(configId)) {
									configsQueues.addAll(rule.getBuildEngines(configId, queueName));
									configsQueues.removeAll(getBuildEnginesByDefinitionAndName(repo, queueName, "skytap%"));
								}
								if (configsQueues.size() > 0) {
									if (loglevel.equalsIgnoreCase("verbose")) {
										System.out.println("No build engines defined for config " + configId + " setting state to stopped");
									}
									configState = "stopped";
								}
								if (loglevel.equalsIgnoreCase("verbose")) {
									System.out.println("Current config state " + configState);
								}
								if (configState == null) {
									rule.removeConfigId(configId);
									rule.getBuildEngines().remove(configId);
								} else if(configState.equalsIgnoreCase("stopped")) {
									if (loglevel.equalsIgnoreCase("verbose")) {
										System.out.println("Removing config " + configId + " since it has stopped");
									}
									Skytap.DeleteConfiguration(configId);
									rule.removeConfigId(configId);
									rule.getBuildEngines().remove(configId);
									// Make the subnet available
									for (int subnetix = 0; subnetix < 255; subnetix = subnetix + 1) {
										if (subNetId[subnetix] != null){
											if (subNetId[subnetix].equals(configId)) {								
												subNetId[subnetix] = null;
												break;
											}
										}
									}
								}
							}
						}
						if (loglevel.equalsIgnoreCase("verbose")) {
							System.out.println("Sleeping for " + sleepTime + " seconds...");
						}
						synchronized(this) {
							try {
								Thread.sleep(sleepTime * 1000);
							} catch (InterruptedException ex) {
								System.out.println("Received service stop command...");
							}
						}
					} catch (IllegalArgumentException e) {
						e.printStackTrace();
					} catch (TeamRepositoryException e) {
						e.printStackTrace();
					}
				}
				repo.logout();
			}
		}
	}

	private void processArguments(String[] args) {
		// Get arguments from properties file first, then allow the command line to override
    	Properties prop = new Properties(); 
    	try {
    		prop.load(new FileInputStream("config.properties"));
    		repositoryURL = prop.getProperty("repository");
    		userId = prop.getProperty("userId");
    		password = prop.getProperty("pass");
    		passwordFile = prop.getProperty("passwordFile");
    		sleepTime = (prop.getProperty("sleepTime") != null) ? Integer.parseInt(prop.getProperty("sleepTime")) : sleepTime;
    		skytapmaxconfigs = Integer.parseInt(prop.getProperty("skytapmaxconfigs"));
    		skytapuser = prop.getProperty("skytapuser");
    		skytapapikey = prop.getProperty("skytapapikey");
    		nettype = prop.getProperty("nettype");
    		netid = prop.getProperty("netid");
    		loglevel = prop.getProperty("loglevel");
    		if (prop.getProperty("buildRules") != null)
    			buildRules = BuildRule.GenerateBuildRules(prop.getProperty("buildRules"));
    	} catch (NumberFormatException ex) {
    		System.out.println("ERROR: -sleepTime option did not have an integer parameter - " + ex.getMessage());
			return;    		
    	} catch (IOException ex) {
    		ex.printStackTrace();
        }
    	
		int argIndex = 0;
		while (argIndex < args.length) {
			String arg = args[argIndex++];
			
			// Capture the repositoryURL
			if (arg.equals("-repository")) {
				repositoryURL = args[argIndex];
			
			// Capture the userId
			} else if (arg.equals("-userId")) {
				userId = args[argIndex];
				
			// Capture the password
			} else if (arg.equals("-pass")) {
				password = args[argIndex];
			
			// Capture the file containing encrypted password
			} else if (arg.equals("-passwordFile")) {
				passwordFile = args[argIndex];
			
			// Capture the skytap user
			} else if (arg.equals("-skytapuser")) {
				skytapuser = args[argIndex];
				
			// Capture the skytap api key
			} else if (arg.equals("-skytapapikey")) {
				skytapapikey = args[argIndex];
					
			// Capture the skytap logging level
			} else if (arg.equals("-loglevel")) {
				loglevel = args[argIndex];
				
			// Capture the skytap network type (icnr, vpn, native)
			} else if (arg.equals("-nettype")) {
			nettype = args[argIndex];
			
			// Capture the skytap network id (icnr=config-id, vpn=vpn-id, native=ignored)
			} else if (arg.equals("-netid")) {
				netid = args[argIndex];

				// Capture the skytap max configs
			} else if (arg.equals("-skytapmaxconfigs")) {
				skytapmaxconfigs = Integer.parseInt(args[argIndex]);
						
			// Capture the build definitions to monitor
			} else if (arg.equals("-buildRules")) {
				buildRules = BuildRule.GenerateBuildRules(args[argIndex]);
			// Capture the optional parameter sleepTime
			} else if (arg.equals("-sleepTime")) {
				try {
					sleepTime = Integer.parseInt(args[argIndex++]);
				} catch (Exception e) {
					System.out.println("ERROR: -sleepTime option did not have an integer parameter - " + e.getMessage());
					return;  // Return null arguments to stop the program
				}
			}
		}
	}
	
	private boolean checkArgs() {
		if (repositoryURL == null) {
			System.out.println("ERROR: Required option -repository not provided.");
		} else if (userId == null) {
			System.out.println("ERROR: Required option -userId not provided.");
		} else if ((password == null) && (passwordFile == null || passwordFile.trim().length() <= 0)) {
			System.out.println("ERROR: Both the options -pass and -passwordFile were not provided.");
		} else if (buildRules == null) {
			System.out.println("ERROR: Required option -buildRules not provided.");
		} else if (skytapuser == null) {
			System.out.println("ERROR: Required option -skytapuser not provided.");
		} else if (skytapapikey == null) {
			System.out.println("ERROR: Required option -skytapapikey not provided.");
		} else {
			return true;
		}
		
		errorProcessingOptions();
		return false;
	}

	private String getDecryptedPassword(String passwordFile) {
		String encryptedPassword = null;
		
		FileInputStream passwordFileStream;
		try {
			passwordFileStream = new FileInputStream(passwordFile);
		} catch (FileNotFoundException e) {
			System.err.println("ERROR: Password file " + passwordFile + " not found.");
			return null;
		}
		
		Properties properties = new Properties();
		
		try {
			properties.loadFromXML(passwordFileStream);
			encryptedPassword = properties.getProperty("password");
			if (encryptedPassword == null) {
				System.err.println("ERROR: No password property in the password file: " + passwordFile);
				return null;
			}
		} catch (InvalidPropertiesFormatException e) {
			// Do nothing
		} catch (IOException e) {
			System.err.println("ERROR: Unable to read password file: " + passwordFile);
			return null;
		}

		if (encryptedPassword == null) {
			StringBuffer passwordBuffer = new StringBuffer();
			try {
				try {
					passwordFileStream.close();
				} catch (IOException e) {
					// Ignore
				}
				passwordFileStream = new FileInputStream(passwordFile);
				byte[] readBuffer = new byte[1024];
				int bytesRead = passwordFileStream.read(readBuffer);
				while (bytesRead != -1) {
					passwordBuffer.append(new String(readBuffer, 0, bytesRead));
					bytesRead = passwordFileStream.read(readBuffer);
				};
				encryptedPassword = passwordBuffer.toString();
			} catch (IOException e) {
				System.err.println("ERROR: Unable to read password file: " + passwordFile);
				return null;
			}
		}
		
		try {
			passwordFileStream.close();
		} catch (IOException e) {
			// Ignore
		}
		
		String decryptedPassword = null;
		
		try {
			decryptedPassword = ObfuscationHelper.decryptString(encryptedPassword);
		} catch (GeneralSecurityException e) {
			System.err.println("ERROR: Unable to decrypt password in password file: " + passwordFile);
			return null;
		} catch (UnsupportedEncodingException e) {
			System.err.println("ERROR: Unable to find the required encoding in password file " + passwordFile);
			return null;
		}
		
		return decryptedPassword;
	}
	
    private ITeamRepository login(IProgressMonitor monitor) throws TeamRepositoryException {
        ITeamRepository repository = TeamPlatform.getTeamRepositoryService().getTeamRepository(repositoryURL);
        repository.registerLoginHandler(new ITeamRepository.ILoginHandler() {
            public ILoginInfo challenge(ITeamRepository repository) {
                return new ILoginInfo() {
                    public String getUserId() {
                        return userId;
                    }
                    public String getPassword() {
                        return password;                     
                    }
                };
            }
        });
        if (loglevel.equalsIgnoreCase("verbose") || loglevel.equalsIgnoreCase("info")) {
        	System.out.println("Contacting RTC Server " + repository.getRepositoryURI() + "...");
        }
        repository.login(monitor);
        if (loglevel.equalsIgnoreCase("verbose") || loglevel.equalsIgnoreCase("info")) {
        	System.out.println("Connected to " + repository.getRepositoryURI() + "...");
        }
        return repository;
    }
	
	private void errorProcessingOptions() {

		System.out.println("\nRequired arguments for Queue Watcher: ");
		System.out.println("\t -repository <repository URL for Rational Team Concert server>");
		System.out.println("\t -buildRules <semi-colon seperated list of build rules>");
		System.out.println("\t -userId <Rational Team Concert User ID used for the build>");
		System.out.println("\t -passwordFile <Password File created using jbe> \n" +
				"\t\tOR -pass <User password>");
		System.out.println("\t -skytapuser <Skytap User ID>");
		System.out.println("\t -skytapapikey <Skytap API Key/Password>");
		
		System.out.println("\nOptional arguments for Queue Watcher: ");
		System.out.println("\t -sleepTime <wait time in seconds before checking for additional Build Requests>");
		System.out.println("\t -skytapmaxconfigs <max number of skytap configs to have running at once, default is no limit, set to 0 to no limit>");
	}
	
	private int buildsInQueue(ITeamRepository repo, String buildDefinitionId) throws IllegalArgumentException, TeamRepositoryException {
		ITeamBuildClient buildClient = ClientFactory.getTeamBuildClient(repo);
		IBuildDefinition buildDefinition = buildClient.getBuildDefinition(buildDefinitionId, NULL_PROGRESS_MONITOR);
		
		if (buildDefinition == null) {
			System.out.println("Error getting build definition " + buildDefinitionId + ", please validate it exists and the user/pass used by this tool have access to it!");
			return -1;
		}
		
		IBuildResultQueryModel buildResultQueryModel = IBuildResultQueryModel.ROOT;
		final IItemQuery query = IItemQuery.FACTORY.newInstance(buildResultQueryModel);

		// Create filter predicates to ensure that the only builds that are returned
		// are ones that have not been started, and matches the Build Definition
		IPredicate matchesBuildDefinition = buildResultQueryModel.buildDefinition()._eq(buildDefinition);
		IPredicate buildNotStarted = buildResultQueryModel.buildState()._eq(BuildState.NOT_STARTED.name());

		// Set the filter
		query.filter(matchesBuildDefinition._and(buildNotStarted));

		// Order by the Build Start Time
		query.orderByAsc(buildResultQueryModel.buildStartTime());

		// NOTE: Adjust the result limits based on the number of builds
		//		       that can be processed.
		//		       Currently only ONE result is being requested.
		//query.setResultLimit(1);

		IItemQueryPage itemQueryPage = buildClient.queryItems(query, IQueryService.EMPTY_PARAMETERS, 1, NULL_PROGRESS_MONITOR);

		return itemQueryPage.getResultSize();
	}
	
	private ArrayList<String> getBuildEnginesByDefinitionAndName(ITeamRepository repo, String buildDefId, String nameLike) throws IllegalArgumentException, TeamRepositoryException {
		ArrayList<String> buildEngines = new ArrayList<String>();
		ITeamBuildClient buildClient = ClientFactory.getTeamBuildClient(repo);
		IBuildDefinition buildDefinition = buildClient.getBuildDefinition(buildDefId, NULL_PROGRESS_MONITOR);
		
		if (buildDefinition == null) {
			System.out.println("Error getting build definition " + buildDefId + ", please validate it exists and the user/pass used by this tool have access to it!");
		} else {			
			IBuildEngineQueryModel buildEngineQueryModel = IBuildEngineQueryModel.ROOT;
			final IItemQuery query = IItemQuery.FACTORY.newInstance(buildEngineQueryModel);
			IPredicate matchesBuildDefinition = buildEngineQueryModel.supportedBuildDefinitions()._contains(buildDefinition);
			IPredicate skytapBuildEngine = buildEngineQueryModel.id()._ignoreCaseLike(nameLike);
			query.filter(matchesBuildDefinition._and(skytapBuildEngine));

			IItemQueryPage itemQueryPage = buildClient.queryItems(query, IQueryService.EMPTY_PARAMETERS, IQueryService.ITEM_QUERY_MAX_PAGE_SIZE, NULL_PROGRESS_MONITOR);
			for(IItemHandle item : itemQueryPage.handlesAsArray())
			{
				buildEngines.add(((IBuildEngine)repo.itemManager().fetchCompleteItem((IBuildEngineHandle)item, 1, NULL_PROGRESS_MONITOR)).getId());
			}
		}
		
		return buildEngines;
	}
}
