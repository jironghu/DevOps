import com.urbancode.air.XTrustProvider;
import com.urbancode.air.plugin.jira.addcomments.FailMode;

XTrustProvider.install();

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

final File resourceHome = new File(System.getenv()['PLUGIN_HOME'])
final def buildLifeId = System.getenv()['AH_BUILD_LIFE_ID']

final def serverUrl       = props['serverUrl']
final def serverVersion   = props['serverVersion'];
final def username        = props['username']
final def password        = props['passwordScript']?:props['password'];

final def issueIds = props['issueIds'].split(',') as List;
final def commentBody     = props['commentBody']
final def failMode        = FailMode.valueOf(props['failMode']);
//------------------------------------------------------------------------------
// Script content
//------------------------------------------------------------------------------

println "Server:\t$serverUrl";
print "Issue Ids:\t"
println issueIds
println "Comment:\t$commentBody"
println "";

// Load the appropriate Jira Soap Classes into classloader
final def jiraSoapJar = new File(resourceHome, "jira-${serverVersion}.wsdl.jar")
assert jiraSoapJar.isFile();
this.class.classLoader.rootLoader.addURL(jiraSoapJar.toURL())
def JiraSoapServiceServiceLocator = Class.forName('com.atlassian.jira.rpc.soap.jirasoapservice_v2.JiraSoapServiceServiceLocator');
def RemoteComment = Class.forName('com.atlassian.jira.rpc.soap.beans.RemoteComment')

def issueNotFoundExceptions = []; // for non FAIL_FAST modes

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
    for (def issueId : issueIds.sort()) {
        def comment = RemoteComment.newInstance();
        comment.setBody(commentBody);
        try {
            jiraSoapService.addComment(sessionToken, issueId, comment);
            println("Successfully added new comment to issue " + issueId);
        }
        catch (Exception e) {
            boolean isNotFoundException = e.message?.startsWith("Could not locate issue with id");
            if (isNotFoundException) {
                println("Issue "+issueId+" not found");
                issueNotFoundExceptions << e;
                if (failMode == FailMode.FAIL_FAST) {
                    throw e;
                }
            }
            else {
                throw e;
            }
        }
    }
}
finally {
    jiraSoapService.logout(sessionToken)
}

// check post conditions
if (failMode == FailMode.FAIL_ON_NO_UPDATES && !issueIds) {
    throw new IllegalStateException("No issues found to update.");
}
if (!issueNotFoundExceptions) {
    println("Failed to add comments to "+issueNotFoundExceptions.size()+" issues");
    if (failMode != FailMode.WARN_ONLY) {
        // throw the first exception we found as our failure
        throw issueNotFoundExceptions.iterator().next();
    }
}
