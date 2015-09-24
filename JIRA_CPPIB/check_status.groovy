import com.urbancode.air.XTrustProvider;
import com.urbancode.air.plugin.jira.addcomments.FailMode;
import java.lang.reflect.Array;

XTrustProvider.install();

//johnliu 10/24/14 add "lib" to the path
final File resourceHome = new File(System.getenv()['PLUGIN_HOME'],"lib")
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

final def statusName = props['statusName']
def issueIds = props['issueIds'].split(',') as List;
final def failMode        = FailMode.valueOf(props['failMode']);

//------------------------------------------------------------------------------
// Script content
//------------------------------------------------------------------------------

println "Server:\t$serverUrl";
println "Status:\t$statusName";
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


// get the changes of the build life from ahptool
println "Found ${issueIds.size()} issue keys";
if (issueIds.isEmpty()) {
    println "No issue keys found in changelog, either there were no changes or GetChangeLogStep was not run.";
    return;
}

def failures = 0;
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
    def statuses = jiraSoapService.getStatuses(sessionToken);
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
                def status = returnedIssue.getStatus();
                for (def remoteStatus : statuses) {
                    if (remoteStatus.getId() == status) {
                         status = remoteStatus.getName();
                         break;
                    }
                }
                if (status != statusName) {
                    failures++;
                    println ("Issue $issueKey status : $status != $statusName");
                    if (failMode == FailMode.FAIL_FAST) {
                       throw new Exception("Issue $issueKey status $status != $statusName");
                    }
                }
                else {
                    println ("Issue $issueKey has correct status : $status");
                }
            }
        }
        catch (Exception e) {
            throw e;
        }
    }
}
finally {
    jiraSoapService.logout(sessionToken)
}
if (failMode == FailMode.FAIL_ON_ANY_FAILURE && failures > 0) {
    println "Got one or more failure!";
    throw new RuntimeException("Something Failed!");
}

if (failMode == FailMode.FAIL_ON_NO_UPDATES && failures == issueIds.size()) {
    println "All failed!";
    throw new RuntimeException("All Failed!");
}
