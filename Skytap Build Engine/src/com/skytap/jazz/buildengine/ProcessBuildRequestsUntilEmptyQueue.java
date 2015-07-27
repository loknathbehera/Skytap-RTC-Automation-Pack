/**
 * Copyright 2015 Skytap Inc.
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

package com.skytap.jazz.buildengine;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ibm.team.build.client.ClientFactory;
import com.ibm.team.build.client.ITeamBuildClient;
import com.ibm.team.build.client.ITeamBuildRequestClient;
import com.ibm.team.build.common.BuildItemFactory;
import com.ibm.team.build.common.model.IBuildDefinition;
import com.ibm.team.build.common.model.IBuildEngine;
import com.ibm.team.build.common.model.IBuildRequest;
import com.ibm.team.build.common.model.IBuildResultHandle;
import com.ibm.team.repository.client.ITeamRepository;
import com.ibm.team.repository.client.TeamPlatform;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.common.UUID;
import com.ibm.team.repository.common.util.ObfuscationHelper;

public class ProcessBuildRequestsUntilEmptyQueue {

	private String buildEngineVersionId = "0.1 beta";
	private String jbePath = null;
	private String engineId = null;
	private int sleepTime = 60;
	private int emptyLoops = 2;
	private String repositoryURL = null;
	private String loglevel = "info";
	private String userId = null;
	private String password = null;
	private String passwordFile = null;
	private String jbeArgs = null;
	private String[] buildDefinitionIds = null;
	private boolean buildEngineExists = false;
	private boolean shutdownAfterExecution = true;
	private boolean stopAsService = false;

	private static final NullProgressMonitor NULL_PROGRESS_MONITOR = new NullProgressMonitor();
	private static ProcessBuildRequestsUntilEmptyQueue pbrueq = new ProcessBuildRequestsUntilEmptyQueue();

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		if (args.length == 1)
		{
			if (args[0].equalsIgnoreCase("start")) {
				pbrueq.start();
			} else {
				pbrueq.stop();
			}
		} else {
			pbrueq.start(args);
		}
	}
	
	public void init(String[] args) {
		// Do nothing as we don't need too for jsvc
	}
	
	public void destroy() {
		// DO nothing as we don't need too for jsvc
	}
	
	public void start() throws IOException {
		pbrueq.start(new String[] {});
	}
	
	public void stop() {
		this.stopAsService = true;
		synchronized(this) {
			this.notify();
		}
	}
	
	public void start(String[] args) throws IOException {
		processArguments(args);
		
		if (checkArgs()) {
			if (passwordFile != null && passwordFile.trim().length() > 0) {
				password = getDecryptedPassword(passwordFile);
			}
			StringBuffer commandArgs = new StringBuffer(jbePath);
			commandArgs.append(jbeArgs);
			
			System.out.println("Skytap Build Engine - Version " + buildEngineVersionId);
			
			TeamPlatform.startup();
			ITeamRepository repo = null;
			try {
				repo = login(NULL_PROGRESS_MONITOR);
			} catch (TeamRepositoryException e) {
				System.err.println("ERROR: Unable to log into the repository.\n Exception message: " + e.getMessage());
			}
			
			if (repo != null) {
				ArrayList<String> goodDefinitions = new ArrayList<String>();
				for(String buildDefinitionId : buildDefinitionIds) {
					if (addBuildEngineToBuildDefinition(repo, buildDefinitionId))
						goodDefinitions.add(buildDefinitionId);
					else
						System.err.println("Error adding build engine to build definition " + buildDefinitionId + "\n");
				}
				
				if (goodDefinitions.isEmpty()) {
					System.err.println("Was unable to add build engine to any build definitions, stopping!");
				} else {
					int loops = 1;
					UUID buildResultUUID = null;
					while (buildResultUUID == null && !stopAsService) {
						StringBuffer jbeCommandArgs = new StringBuffer(commandArgs);
						if (loglevel.equalsIgnoreCase("verbose")) {
							System.out.println("Checking for pending builds...");
						}
						buildResultUUID = getNextBuildRequest(repo);
						if (buildResultUUID != null) {
							if (loglevel.equalsIgnoreCase("verbose")) {
								System.out.println("Pending build found, Build Result UUID " + buildResultUUID);
							}
							//
							// Found a pending Build Request.  Delegate the processing.
							//
							jbeCommandArgs.append(" -buildResultUUID ");
							jbeCommandArgs.append(buildResultUUID.getUuidValue());
							int jbeExitCode = invokeJBE(jbeCommandArgs.toString());
							if (loglevel.equalsIgnoreCase("verbose")) {
								System.out.println("Exit code from jbe when executing Build Result UUID " 
									+ buildResultUUID.getUuidValue() + " is " + jbeExitCode);
							}
							buildResultUUID = null;
							loops = 1;
						} else {
							if (emptyLoops != -1) {
								if (loops >= emptyLoops) {
									System.out.println("No builds found for " + loops + " loops, exiting!");
									break;
								}
								loops++;
							}
							if (loglevel.equalsIgnoreCase("verbose")) {
								System.out.println("No pending builds found, waiting...");
							}
							synchronized(this) {
								try {
									Thread.sleep(sleepTime * 1000);
								} catch (InterruptedException e) {
									System.out.println("INFO: Service stop received...");
									break;
								}
							}
						}
					}
							
					for(String buildDefinitionId : goodDefinitions.toArray(new String[goodDefinitions.size()])) {
						removeBuildEngineFromBuildDefinition(repo, buildDefinitionId);
					}
				}
				
				deleteBuildEngine(repo);	
				repo.logout();
			}
			
			TeamPlatform.shutdown();
			
			if (shutdownAfterExecution && !stopAsService)
				shutdown();
		}
	}
	
	private boolean shutdown() throws IOException {
	    String shutdownCommand = null;

	    if(isOSName("AIX"))
	        shutdownCommand = "shutdown -Fh now";
	    else if(isOSName("FreeBSD") || isOSName("Linux") || isOSName("LINUX") || isOSName("Mac")|| isOSName("Mac OS X") || isOSName("NetBSD") || isOSName("OpenBSD"))
	        shutdownCommand = "shutdown -h now";
	    else if(isOSName("HP-UX"))
	        shutdownCommand = "shutdown -hy 1";
	    else if(isOSName("Irix"))
	        shutdownCommand = "shutdown -y -g 1";
	    else if(isOSName("Solaris") || isOSName("SunOS"))
	        shutdownCommand = "shutdown -y -i5 -g0";
	    else if(isOSName("Windows"))
	        shutdownCommand = "shutdown.exe -s -t 0";
	    else
	        return false;

	    Runtime.getRuntime().exec(shutdownCommand);
	    return true;
	}
	
	private String OS_NAME = System.getProperty("os.name");
	private boolean isOSName(String osName) {
		return OS_NAME.startsWith(osName);
	}

	private void processArguments(String[] args) {
		// Get arguments from properties file first, then allow the command line to override
    	Properties prop = new Properties(); 
    	try {
    		prop.load(new FileInputStream("config.properties"));
    		jbePath = prop.getProperty("jbePath");
    		repositoryURL = prop.getProperty("repository");
    		loglevel = prop.getProperty("loglevel");
    		userId = prop.getProperty("userId");
    		password = prop.getProperty("pass");
    		passwordFile = prop.getProperty("passwordFile");
    		shutdownAfterExecution = (prop.getProperty("shutdownAfterExecution") != null && prop.getProperty("shutdownAfterExecution").trim().length() > 0) ? Boolean.valueOf(prop.getProperty("shutdownAfterExecution")) : shutdownAfterExecution;
    		sleepTime = (prop.getProperty("sleepTime") != null) ? Integer.parseInt(prop.getProperty("sleepTime")) : sleepTime;
    		emptyLoops = (prop.getProperty("emptyLoops") != null) ? Integer.parseInt(prop.getProperty("emptyLoops")) : emptyLoops;
    		if (prop.getProperty("buildDefinitionIds") != null)
    			buildDefinitionIds = prop.getProperty("buildDefinitionIds").split(",");
    	} catch (NumberFormatException ex) {
    		errorProcessingOptions("ERROR: -sleepTime and/or -emptyLoops option did not have a valid integer parameter - " + ex.getMessage());
			return;    		
    	} catch (IOException ex) {
    		System.out.println("WARN: No config.properties file found, using command line arguments only!");
        }
    	
		StringBuffer arguments = new StringBuffer();
		int argIndex = 0;
		while (argIndex < args.length) {
			String arg = args[argIndex++];
			
			// Capture the path of the Rational Team Concert Build System toolkit's
			// jbe program.
			if (arg.equals("-jbePath")) {
				jbePath = args[argIndex++];
				if (loglevel.equalsIgnoreCase("verbose")) {
					System.out.println("jbePath = "+ jbePath);
				}
			
			// Capture the buildDefinitionIds
			} else if (arg.equals("-buildDefinitionIds")) {
				buildDefinitionIds = args[argIndex++].split(",");

			// Capture the repositoryURL AND include it in the jbe arguments
			} else if (arg.equals("-repository")) {
				repositoryURL = args[argIndex];
			
			// Capture the userId AND include it in the jbe arguments
			} else if (arg.equals("-userId")) {
				userId = args[argIndex];
				
			// Capture the password AND include it in the jbe arguments
			} else if (arg.equals("-pass")) {
				password = args[argIndex];
			
			// Capture the file containing encrypted AND include it in 
			// the jbe arguments
			} else if (arg.equals("-passwordFile")) {
				passwordFile = args[argIndex];
				
			// Capture the optional parameter sleepTime
			} else if (arg.equals("-sleepTime")) {
				try {
					sleepTime = Integer.parseInt(args[argIndex++]);
				} catch (Exception e) {
					errorProcessingOptions("ERROR: -sleepTime option did not have an integer parameter - " + e.getMessage());
					return;  // Return null arguments to stop the program
				}
			
			// Capture the optional parameter emptyLoops
			} else if (arg.equals("-emptyLoops")) {
				try {
					emptyLoops = Integer.parseInt(args[argIndex++]);
				} catch (Exception e) {
					errorProcessingOptions("ERROR: -emptyLoops option did not have an integer parameter - " + e.getMessage());
					return;  // Return null arguments to stop the program
				}
			
			// Capture the optional parameter shutdownAfterExecution
			} else if (arg.equals("-shutdownAfterExecution")) {
				shutdownAfterExecution = Boolean.valueOf(args[argIndex++]);
				
			// Capture any other arguments and just pass them on to JBE
			} else {
				arguments.append(' ').append(arg);
			}
		}
		
		arguments.append(' ').append("-repository ").append(repositoryURL);
		arguments.append(' ').append("-userId ").append(userId);
		if (passwordFile != null && passwordFile.trim().length() > 0) {
			arguments.append(' ').append("-passwordFile ").append(passwordFile);
		} else {
			arguments.append(' ').append("-pass ").append(password);
		}
		
		engineId = "Skytap-" + String.valueOf(java.util.UUID.randomUUID());
		arguments.append(' ').append("-engineId ").append(engineId);

		jbeArgs = arguments.toString();
	}
	
	private boolean checkArgs() {
		if (jbePath == null) {
			errorProcessingOptions("ERROR: Required option -jbePath not provided.");
		} else if (buildDefinitionIds == null) {
			errorProcessingOptions("ERROR: Required option -buildDefinitionIds not provided.");
		} else if (engineId == null) {
			errorProcessingOptions("ERROR: Required option -engineId not provided.");
		} else if (repositoryURL == null) {
			errorProcessingOptions("ERROR: Required option -repository not provided.");
		} else if (userId == null) {
			errorProcessingOptions("ERROR: Required option -userId not provided.");
		} else if ((password == null) && (passwordFile == null)) {
			errorProcessingOptions("ERROR: Both the options -pass and -passwordFile were not provided.");
		} else if (jbeArgs != null) {  // Null arguments means there was an error
			return true;
		}
		
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
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private boolean addBuildEngineToBuildDefinition(ITeamRepository repo, String buildDefinitionId) {
		ITeamBuildClient buildClient = ClientFactory.getTeamBuildClient(repo);
		IBuildDefinition buildDefinition = null;
		try {
			buildDefinition = buildClient.getBuildDefinition(buildDefinitionId, NULL_PROGRESS_MONITOR);
		} catch (IllegalArgumentException e1) {
			System.err.println("ERROR: Invalid Build Definition Id provided - " + buildDefinitionId);
			return false;
		} catch (TeamRepositoryException e) {
			System.err.println("ERROR while attempting to access the repository - " + e.getMessage());
			return false;
		}
		try {
			IBuildEngine buildEngine = null;
			
			if (buildEngineExists) {
				buildEngine = buildClient.getBuildEngine(engineId, NULL_PROGRESS_MONITOR);
				buildEngine = (IBuildEngine) buildEngine.getWorkingCopy();				
			} else {
				buildEngine = BuildItemFactory.createBuildEngine();
				buildEngine.setId(engineId);
				buildEngine.setActive(true);
				buildEngineExists = true;
			}
			
			buildEngine.setProcessArea(buildDefinition.getProcessArea());
			List supportedBuildDefinitions = buildEngine.getSupportedBuildDefinitions();
			supportedBuildDefinitions.add(buildDefinition);
			buildClient.save(buildEngine, NULL_PROGRESS_MONITOR);
			return true;
		} catch (IllegalArgumentException e) {
			System.err.println("ERROR: Invalid attribute values provided for Build Engine - " + e.getMessage());
			e.printStackTrace();
		} catch (TeamRepositoryException e) {
			System.err.println("ERROR while creating Build Engine - " + e.getMessage());
		} catch (Exception e) {
			System.err.println("ERROR: Build Engine ID " + engineId + 
					"\n could not be created or associated with Build Definition \n" +
					buildDefinitionId + ".");
		}
		
		return false;
	}

	private UUID getNextBuildRequest(ITeamRepository repo) {
		ITeamBuildClient buildClient = ClientFactory.getTeamBuildClient(repo);
		ITeamBuildRequestClient buildRequestClient = ClientFactory.getTeamBuildRequestClient(repo);

		try {
			IBuildEngine buildEngine = buildClient.getBuildEngine(engineId, NULL_PROGRESS_MONITOR);
			IBuildRequest buildRequest = buildRequestClient.getNextRequest(buildEngine, 
					new String[] {IBuildRequest.PROPERTY_BUILD_RESULT}, NULL_PROGRESS_MONITOR);
			if (buildRequest != null) {
				IBuildResultHandle buildResultHandle = buildRequest.getBuildResult();
				if (buildResultHandle != null) {
					return buildResultHandle.getItemId();
				} else {
					System.err.println("ERROR: Could not get the Build Result UUID from the next Build Request.");
				}
			}
			return null;
		} catch (IllegalArgumentException e) {
			System.err.println("ERROR: Invalid engine Id.");
		} catch (TeamRepositoryException e) {
			System.err.println("ERROR: Unable to retrieve next Build Request - " + e.getMessage());
		}
		return null;
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
        System.out.println("Contacting RTC Server " + repository.getRepositoryURI() + "...");
        repository.login(monitor);
        System.out.println("Connected to " + repository.getRepositoryURI() + "...");
        return repository;
    }

	private int invokeJBE(String command) {
		if (loglevel.equalsIgnoreCase("verbose")) {
			System.out.println("Invoking jbe with the following command:\n" + command);
		}
		
		try {
			Process p = Runtime.getRuntime().exec(command);
			StreamReader processErrorStream = new StreamReader(p.getErrorStream());
			StreamReader processInputStream = new StreamReader(p.getInputStream());
			processErrorStream.start();
			processInputStream.start();
			int exitCode = p.waitFor();
			if (loglevel.equalsIgnoreCase("verbose")) {
				System.err.println("Error output from command: \n");
				System.err.println(processErrorStream.getStreamData());
				System.out.println("Output from command: \n");
				System.out.println(processInputStream.getStreamData());
			}
			return exitCode;
		} catch (IOException e) {
			System.err.println("ERROR executing command: " + e.getMessage());
		} catch (InterruptedException e) {
			System.out.println("jbe command was terminted by user interruption.");
		}
		return 0;
	}
	
	@SuppressWarnings("rawtypes")
	private boolean removeBuildEngineFromBuildDefinition(ITeamRepository repo, String buildDefinitionId) {
		ITeamBuildClient buildClient = ClientFactory.getTeamBuildClient(repo);
		try {
			IBuildDefinition buildDefinition = 
					buildClient.getBuildDefinition(buildDefinitionId, NULL_PROGRESS_MONITOR);
			IBuildEngine buildEngine = buildClient.getBuildEngine(engineId, NULL_PROGRESS_MONITOR);
			buildEngine = (IBuildEngine) buildEngine.getWorkingCopy();
			buildEngine.setActive(false);
			List supportedBuildDefinitions = buildEngine.getSupportedBuildDefinitions();
			boolean returnValue = supportedBuildDefinitions.remove(buildDefinition);
			buildClient.save(buildEngine, NULL_PROGRESS_MONITOR);
			return returnValue;
		} catch (IllegalArgumentException e) {
			System.err.println("ERROR: Invalid Build Definition Id provided - " + buildDefinitionId);
		} catch (TeamRepositoryException e) {
			System.err.println("ERROR while attempting to access the repository - " + e.getMessage());
		} catch (Exception e) {
			System.err.println("ERROR: Build Engine ID " + engineId + 
					" could not be deactivated or disassociated from Build Definition " +
					buildDefinitionId + ".");
		}
		
		return false;
	}
	
	private void deleteBuildEngine(ITeamRepository repo) {
		ITeamBuildClient buildClient = ClientFactory.getTeamBuildClient(repo);
		IBuildEngine buildEngine;
		try {
			buildEngine = buildClient.getBuildEngine(engineId, NULL_PROGRESS_MONITOR);
			buildClient.delete(buildEngine, NULL_PROGRESS_MONITOR);
		} catch (IllegalArgumentException e) {
			System.err.println("ERROR: invalid Engine ID " + engineId + " - " + e.getMessage());
		} catch (TeamRepositoryException e) {
			System.err.println("ERROR: Unable to delete Engine ID " + engineId + " - " + e.getMessage());
		}
	}

	private void errorProcessingOptions(String error) {
		System.err.println(error);

		System.out.println("\nRequired arguments for OnRequest Jazz Build Engine program: ");
		System.out.println("\t -jbePath <Path for the Rational Team Concert Build System Toolkit's jbe program>");
		System.out.println("\t -buildDefinitionIds <Comma seperated list of Build Definition IDs>");
		System.out.println("\t -repository <repository URL for Rational Team Concert server>");
		System.out.println("\t -userId <Rational Team Concert User ID used for the build");
		System.out.println("\t -passwordFile <Password File created using jbe> \n" +
				"\t\tOR -pass <User password>");

		System.out.println("\nOptional arguments for RFRS wrapper for Jazz Build Engine program: ");
		System.out.println("\t -sleepTime <wait time in seconds before checking for additional Build Requests> (Default=60)");
		System.out.println("\t -emptyLoops <number of times to check for any Build Requests prior to exiting> (Default=2)");
		System.out.println("\t -shutdownAfterExecution [true/false] <shutdown the machine after number of loops> (Default=true)");

		System.out.println("\nRun jbe without any arguments to learn more about the additional arguments.");
	}
	
	private class StreamReader extends Thread {
		StringBuffer streamData = new StringBuffer();
		private InputStream inputStream;
		
		public StreamReader(InputStream inputStream) {
			this.inputStream = inputStream;
		}
		
		@Override
		public void run() {
			BufferedReader b = new BufferedReader(new InputStreamReader(inputStream));
			String line;
			try {
				line = b.readLine();
				while (line != null) {
					streamData.append(line);
					streamData.append('\n');
					line = b.readLine();
				}
				b.close();
			} catch (IOException e) {
				System.err.println("ERROR reading output from the command.");
			}
		}
		
		public String getStreamData() {
			return streamData.toString();
		}
	}
}
