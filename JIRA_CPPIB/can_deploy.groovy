import com.urbancode.air.XTrustProvider;
import com.urbancode.air.plugin.jira.addcomments.FailMode;
import java.lang.reflect.Array;


XTrustProvider.install();

final File resourceHome = new File(System.getenv()['PLUGIN_HOME'],"lib")
final def buildLifeId = System.getenv()['AH_BUILD_LIFE_ID']

// Get Input Properties
final def props = new Properties();
final def inputPropsFile = new File(args[0]);
final def inputPropsStream = null;
try {
	inputPropsStream = new FileInputStream(inputPropsFile);
	props.load(inputPropsStream);
}
catch (IOException e) {
	throw new RuntimeException(e);
}

// Set Output Properties
final def outProps = new Properties();
final def outPropsStream = new FileOutputStream(args[1]);

// Write empty JIRA no to out properties
final def Ids = props['issueIds'];
println "Ids:$Ids";
if (Ids.isEmpty()) {
	println 'JIRA no is empty, bypass checking'
	outProps.BMCNumber = 'N/A'
	outProps.store(outPropsStream, null)
	System.exit(0)
}

def issueIds = Ids.split(',') as List;
println "Found ${issueIds.size()} issue keys";
// Get all properties
final def serverUrl       = props['serverUrl']
final def serverVersion   = props['serverVersion'];
final def username        = props['username']
final def password        = props['passwordScript']?:props['password'];
final def failMode        = FailMode.valueOf(props['failMode']);
final def customFieldNameEnv = "customfield_10011"; ////Environment: customFieldId=10011 for IT
final def customFieldNameBMCSD = "customfield_10511"; //BMC Scheduled Date/Time: customFieldId=10511
final def customFieldNameBMCRefNo = "customfield_10112";  //BMC Ref NO: customFieldId=10112

println "Server:$serverUrl";
print "Issue Keys:\t";
println issueIds;
println "";

// Load the appropriate Jira Soap Classes into classloader
final def jiraSoapJar = new File(resourceHome, "jira-${serverVersion}.wsdl.jar")
assert jiraSoapJar.isFile();
this.class.classLoader.rootLoader.addURL(jiraSoapJar.toURL())
def JiraSoapServiceServiceLocator = Class.forName('com.atlassian.jira.rpc.soap.jirasoapservice_v2.JiraSoapServiceServiceLocator');
def RemoteIssue = Class.forName('com.atlassian.jira.rpc.soap.beans.RemoteIssue')
def RemoteComment = Class.forName('com.atlassian.jira.rpc.soap.beans.RemoteComment')
def RemoteFieldValue = Class.forName('com.atlassian.jira.rpc.soap.beans.RemoteFieldValue')
def RemotePermissionException = Class.forName('com.atlassian.jira.rpc.exception.RemotePermissionException')
//added new line for remote custom field value
def RemoteCustomFieldValue = Class.forName('com.atlassian.jira.rpc.soap.beans.RemoteCustomFieldValue')

def failures = 0;
def String msg = "";
// connect to Jira
String fullAddress = serverUrl + "/rpc/soap/jirasoapservice-v2";
if (!fullAddress.startsWith("http")) {
	fullAddress = "http://" + fullAddress;
}
def locator = JiraSoapServiceServiceLocator.newInstance();
locator.jirasoapserviceV2EndpointAddress = fullAddress;
def jiraSoapService = locator.getJirasoapserviceV2();
def sessionToken = jiraSoapService.login(username, password);

// Get the number to value mapping
def issueTypesMap = new HashMap<String,String>();
def issueStatusesMap = new HashMap<String,String>();
//println ("\nHere is the list of Issue Types ID and Names:");
def issueTypes = jiraSoapService.getIssueTypes(sessionToken);
for (def remoteIssueType : issueTypes) {
	String name = remoteIssueType.getName();
	String id = remoteIssueType.getId();
	//println("Id:" + id + " " + "Name:" + name);
	issueTypesMap.put(id, name);
	//String value = issueTypesMap.get(id).toString();
	//println("Id:" + id + " " + "value:" + value);

}

//println ("\nHere is the list of Status ID and Names:");
def statuses = jiraSoapService.getStatuses(sessionToken);
for (def remoteStatus : statuses) {
	String name = remoteStatus.getName();
	String id = remoteStatus.getId();
	//println("Id:" + id + " " + "Name:" + name);
	issueStatusesMap.put(id, name);
}

try {
	//def statuses = jiraSoapService.getStatuses(sessionToken);
	for (def issueKey : issueIds) {
		try {

			def returnedIssue = jiraSoapService.getIssue(sessionToken, issueKey)
			if (!returnedIssue) {
				println("\tSkipping Issue "+issueKey+": Specified Issue not found.");
				failures++;
				if (failMode == FailMode.FAIL_FAST) {
					throw new Exception("Issue not found for $issueKey");
				}
			}
			else {
				// Get System Field values
				String type = returnedIssue.getType();
				String status = returnedIssue.getStatus();
				// Verify
				String type_s = issueTypesMap.get(type).toString();
				String status_s = issueStatusesMap.get(status).toString();
				println("\tIssue type: $type=$type_s");
				println("\tIssue status: $status=$status_s");
				
				if ("Deployment" != type_s) {
					failures++;
					msg = "Issue Type is not Deployment."
				}
				if (("Opened" == status_s) || ("Closed" == status_s) || ("Implemented" == status_s)) {
					failures++;
					msg = msg + "Issue Status is $status_s."
				}

				def customFieldValues = returnedIssue.getCustomFieldValues(); //method to get all custom Field Values
				//for (RemoteCustomFieldValue remoteCustomFieldValue : customFieldValues) {
				//	String[] values = remoteCustomFieldValue.getValues();
				//	for (String value : values) {
				//		System.out.println("Value for CF with Id:" + remoteCustomFieldValue.getCustomfieldId() + " -" + value);
				//	}
				//}
				
				// Get Environment field value
				def value_set_env=0;
				for (def remoteValue : customFieldValues) {
					if (remoteValue.getCustomfieldId() == customFieldNameEnv) {
						def values = remoteValue.getValues();
						if (values.length != 0) {
							String envs = values.toString();
							envs = envs.substring(1, envs.length()-1)
							println ("Issue $issueKey ($customFieldNameEnv)/Environment has a value of: $envs");
							// Write to out properties
							try {
								outProps.setProperty("Environment", "$envs");
								outProps.store(outPropsStream,null);
							}
							catch (IOException e) {
								throw new RuntimeException(e);
							}
							value_set_env=1;
							// Compare environment in UrbanCode and JIRA
							//def value = values.toString();
							//value = value.replace('[','');
							//value = value.replace(']','');
							//if (!(env.compareTo(value) == 0)) {
							///	failures++;
							//	msg = msg + "Environment doesn't match.";
							//}
							break;
						}
						else {
							msg = msg + "[Environment field is empty]";
							//throw new Exception("Issue $issueKey custom field '$customFieldName' has no value!");
						}
					}
				}
				//if we get to this point then the custom field was not found
				if (value_set_env!=1) {
					failures++;
					msg = msg + "[Environment field is not found]";
				}///

				// Get BMC Scheduled Date/Time field value
				def value_set_sdt=0;
				for (def remoteValue : customFieldValues) {
					if (remoteValue.getCustomfieldId() == customFieldNameBMCSD) {
						def values = remoteValue.getValues();
						if (values.length != 0) {							
							value_set_sdt=1;
							//Scheduled Date/Time has a value of: [21/Sep/12 2:01 PM]
							//For deployment in UAT and PROD, if the deployment starts before the JIRA's scheduled date/time, then deployment fails.
							String dt = values.toString();
							dt = dt.substring(1, dt.length()-1)
							println ("Issue $issueKey ($customFieldNameBMCSD)/Scheduled Date/Time has a value of: $dt");
							//Date.parse('dd/MMM/yy HH:mm a', "21/Sep/12 2:01 PM") <=> new Date()
							Date scheduled = Date.parse('dd/MMM/yy HH:mm a', dt)   // scheduled date/time
							Date today = new Date(); // the time doing the actual deploy
							if (today.before(scheduled)) {
								failures++;
								msg = msg + "Too early";
							}					
							break;
						}
						else {
							msg = msg + "[BMC Scheduled Date/Time is empty]";
							//throw new Exception("Issue $issueKey custom field '$customFieldName' has no value!");
						}
					}
				}
				//if we get to this point then the custom field was not found
				if (value_set_sdt!=1) {
					failures++;
					msg = msg + "[BMC Scheduled Date/Time field is not found]";
				}///

			
				// Get BMC Ref No field value
				def value_set_brn=0;
				for (def remoteValue : customFieldValues) {
					if (remoteValue.getCustomfieldId() == customFieldNameBMCRefNo) {
						def values = remoteValue.getValues();
						if (values.length != 0) {
							// Remove [ and  ]
							String bfn = values.toString();							
							bfn = bfn.substring(1, bfn.length()-1)
							println ("Issue $issueKey ($customFieldNameBMCRefNo)/BMC Ref No has a value of: $bfn");
							// Write to out properties
							try {
								outProps.setProperty("BMCNumber", "$bfn");
								outProps.store(outPropsStream,null);
							}
							catch (IOException e) {
								throw new RuntimeException(e);
							}
							value_set_brn=1;
							break;
						}
						else {
							msg = msg + "[BMC Ref No is empty]";
							//throw new Exception("Issue $issueKey custom field '$customFieldName' has no value!");
						}
					}
				}
				//if we get to this point then the custom field was not found
				if (value_set_brn!=1) {
					failures++;
					msg = msg + "[BMC Ref No field is not found]";
				} ///
				
			}
		}

		catch (Exception e) {
			throw e;
		}
	}
}
finally {
	jiraSoapService.logout(sessionToken)
	println ("failures=$failures and msg=$msg");
}
if (failMode == FailMode.FAIL_ON_ANY_FAILURE && failures > 0) {
	println "Got one or more failure!";
	throw new RuntimeException(msg);
}

if (failMode == FailMode.FAIL_ON_NO_UPDATES && failures == issueIds.size()) {
	println "All failed!";
	throw new RuntimeException("All Failed!");
}

System.exit(0)
