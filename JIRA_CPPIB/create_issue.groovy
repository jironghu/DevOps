import com.urbancode.air.XTrustProvider;
import com.urbancode.air.plugin.jira.addcomments.FailMode;

XTrustProvider.install();

final File resourceHome = new File(System.getenv()['PLUGIN_HOME'])
final def buildLifeId = System.getenv()['AH_BUILD_LIFE_ID']

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

final def serverUrl       = props['serverUrl']
final def serverVersion   = props['serverVersion'];
final def username        = props['username']
final def password        = props['passwordScript']?:props['password'];
final def projectKey = props['projectKey']
final def assignee = props['assignee']
final def summary = props['summary']
final def environment = props['environment']
final def issueDescription = props['issueDescription']
final def issueTypeName = props['issueTypeName'];

// CONSTANTS
final def priorityId  = "4";                    // minor
final def dueDate     = Calendar.getInstance(); // right now

//------------------------------------------------------------------------------
// Script content
//------------------------------------------------------------------------------

println "Server:\t$serverUrl";
println "Project:\t$projectKey";
println "";

// Load the appropriate Jira Soap Classes into classloader
final def jiraSoapJar = new File(resourceHome, "jira-${serverVersion}.wsdl.jar")
assert jiraSoapJar.isFile();
this.class.classLoader.rootLoader.addURL(jiraSoapJar.toURL())
def JiraSoapServiceServiceLocator = Class.forName('com.atlassian.jira.rpc.soap.jirasoapservice_v2.JiraSoapServiceServiceLocator');
def RemoteIssue = Class.forName('com.atlassian.jira.rpc.soap.beans.RemoteIssue')

// connect to Jira
String fullAddress = serverUrl + "/rpc/soap/jirasoapservice-v2";
if (!fullAddress.startsWith("http")) {
    fullAddress = "http://" + fullAddress;
}
def locator = JiraSoapServiceServiceLocator.newInstance();
locator.jirasoapserviceV2EndpointAddress = fullAddress;
def jiraSoapService = locator.getJirasoapserviceV2();
def sessionToken = jiraSoapService.login(username, password);
try {
    def issueTypeLookup = jiraSoapService.getIssueTypes(sessionToken).toList().groupBy{it.name}
    def issueTypeId = issueTypeLookup[issueTypeName];

    if (!issueTypeId?.first()?.id?.trim()) {
        throw new Exception("Issue Type for $issueTypeName not found.");
    }

    def issue = RemoteIssue.newInstance()
    issue.project  = projectKey
    issue.type     = issueTypeId?.first()?.id
    issue.priority = priorityId
    issue.duedate  = dueDate
    issue.assignee = assignee

    if (summary) {
        issue.summary = summary
    }
    if (environment) {
        issue.environment = environment
    }
    if (issueDescription) {
        issue.description = issueDescription
    }

    def resultIssue = jiraSoapService.createIssue(sessionToken, issue)
    println "Successfully created issue $resultIssue.key";
    def status = jiraSoapService.getStatuses(sessionToken).find{it.id == resultIssue.status};
    println "\tStatus Name = $status.name";
    def resolution = jiraSoapService.getResolutions(sessionToken).find{it.id == resultIssue.resolution};
    println "\tResolution Name = ${resolution?.name  ?: 'Unresolved'}"
}
finally {
    jiraSoapService.logout(sessionToken)
}
