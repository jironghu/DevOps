import groovy.xml.StreamingMarkupBuilder;

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
final def outputFile = props['outputFile'];

final def issueIds = props['issueIds'].split(',');

//------------------------------------------------------------------------------
// Script content
//------------------------------------------------------------------------------

println "Server:\t$serverUrl";
print "Issue Key Pattern:\t"
println issueIds
println "";

//Load the appropriate Jira Soap Classes into classloader
final def jiraSoapJar = new File(resourceHome, "jira-${serverVersion}.wsdl.jar")
assert jiraSoapJar.isFile();
this.class.classLoader.rootLoader.addURL(jiraSoapJar.toURL())
def JiraSoapServiceServiceLocator = Class.forName('com.atlassian.jira.rpc.soap.jirasoapservice_v2.JiraSoapServiceServiceLocator');


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
    def statusLookup = jiraSoapService.getStatuses(sessionToken).toList().groupBy{it.id}
    def issueTypeLookup = jiraSoapService.getIssueTypes(sessionToken).toList().groupBy{it.id}

    def builder = new StreamingMarkupBuilder()
    builder.encoding = 'UTF-8'
    def issuesXml = builder.bind {
        issues() {
            for (def issueId : issueIds) {
                def issueKey = issueId;

                def jiraIssue = jiraSoapService.getIssue(sessionToken, issueKey)
                if (!jiraIssue) {
                    println("\tSkipping Issue "+issueKey+": Specified Issue not found.");
                }
                else {
                    issue(id:issueKey, "issue-tracker":"JIRA") {
                        name(jiraIssue.summary)
                        description(jiraIssue.description)
                        type(issueTypeLookup[jiraIssue.type]?.name)
                        status(statusLookup[jiraIssue.status]?.name)
                    }
                }
            }
        }
    }

    def outFile = new File(outputFile);
     outFile << issuesXml;
    //println issuesXml;
    println("Results uploaded successfully")
}
finally {
    jiraSoapService.logout(sessionToken)
}
