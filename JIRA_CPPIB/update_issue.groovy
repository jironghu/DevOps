import com.urbancode.air.XTrustProvider;
import com.urbancode.air.plugin.jira.addcomments.FailMode;
import java.lang.reflect.Array;

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

final def actionName = props['actionName']
final def additionalComment = props['additionalComment']
def issueIds = props['issueIds'].split(',') as List;

//------------------------------------------------------------------------------
// Script content
//------------------------------------------------------------------------------

println "Server:\t$serverUrl";
println "Action:\t$actionName";
print "Issue Keys:\t";
println issueIds;
println "Additional Comment:\t$additionalComment";
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
    for (def issueKey : issueIds) {
        try {
            def returnedIssue = jiraSoapService.getIssue(sessionToken, issueKey)
            if (!returnedIssue) {
                println("\tSkipping Issue "+issueKey+": Specified Issue not found.");
            }
            else {
                def action = jiraSoapService.getAvailableActions(sessionToken, issueKey).find{actionName == it.name}
                if (action == null) {
                    println("\tSkipping Issue $issueKey: Specified Action $actionName was not available.");
                }
                else {
                    def fields = jiraSoapService.getFieldsForAction(sessionToken, issueKey, action.id)

                    def fieldValues = []
                    for (def field : fields) {
                        if (field.name == 'assignee') {
                            fieldValues << RemoteFieldValue.newInstance('assignee', [issue.assignee] as String[])
                        }
                    }
                    def remoteFieldValueArray = Array.newInstance(RemoteFieldValue, 0)
                    jiraSoapService.progressWorkflowAction(sessionToken, issueKey, action.id, fieldValues.toArray(remoteFieldValueArray))
                    println("Successfully performed $action.name on issue $issueKey");
                    if (additionalComment) {
                        def comment = RemoteComment.newInstance()
                        comment.setBody(additionalComment);
                        jiraSoapService.addComment(sessionToken, issueKey, comment)
                        println("Successfully added new comment to issue $issueKey");
                    }
                }
            }
        }
        catch (Exception e) {
            if (!RemotePermissionException.isInstance(e)) {
                throw e;
            }
            else {
                e.printStackTrace();
            }
        }
    }
}
finally {
    jiraSoapService.logout(sessionToken)
}
