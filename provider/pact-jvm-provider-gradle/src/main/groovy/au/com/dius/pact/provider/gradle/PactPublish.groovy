package au.com.dius.pact.provider.gradle

import groovy.transform.ToString

/**
 * Config for pact publish task
 */
@ToString
class PactPublish {
    def pactDirectory
    String pactBrokerUrl
    String version
    String pactBrokerToken
    String pactBrokerUsername
    String pactBrokerPassword
    String pactBrokerAuthenticationScheme
    List<String> tags = []
    String include = '.*\\.json'
    List<String> excludes = []
}
