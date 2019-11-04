package au.com.dius.pact.provider.reporters

import au.com.dius.pact.core.model.Request
import au.com.dius.pact.core.model.RequestResponseInteraction
import au.com.dius.pact.core.model.Response
import au.com.dius.pact.provider.ConsumerInfo
import au.com.dius.pact.provider.ProviderInfo
import spock.lang.Specification

@SuppressWarnings('UnnecessaryObjectReferences')
class MarkdownReporterSpec extends Specification {

  private File reportDir

  def setup() {
    reportDir = File.createTempDir()
  }

  def cleanup() {
    reportDir.deleteDir()
  }

  def 'does not overwrite the previous report file'() {
    given:
    def reporter = new MarkdownReporter('test', reportDir)
    def provider1 = new ProviderInfo(name: 'provider1')
    def provider2 = new ProviderInfo(name: 'provider2')

    when:
    reporter.initialise(provider1)
    reporter.finaliseReport()
    reporter.initialise(provider2)
    reporter.finaliseReport()

    then:
    reportDir.list().sort() as List == ['provider1.md', 'provider2.md']
  }

  def 'appends to an existing report file'() {
    given:
    def reporter = new MarkdownReporter('test', reportDir)
    def provider1 = new ProviderInfo(name: 'provider1')
    def consumer = new ConsumerInfo(name: 'Consumer')
    def interaction1 = new RequestResponseInteraction('Interaction 1', [], new Request(), new Response())
    def interaction2 = new RequestResponseInteraction('Interaction 2', [], new Request(), new Response())

    when:
    reporter.initialise(provider1)
    reporter.reportVerificationForConsumer(consumer, provider1, 'staging')
    reporter.interactionDescription(interaction1)
    reporter.finaliseReport()
    reporter.initialise(provider1)
    reporter.reportVerificationForConsumer(consumer, provider1, 'production')
    reporter.interactionDescription(interaction2)
    reporter.finaliseReport()

    def results = new File(reportDir, 'provider1.md').text

    then:
    results.contains('## Verifying a pact between _Consumer_ and _provider1_ for tag staging\n\nInteraction 1 ')
    results.contains('## Verifying a pact between _Consumer_ and _provider1_ for tag production\n\nInteraction 2 ')
  }

  def 'does not specify tag if not tag is not specified'() {
    given:
    def reporter = new MarkdownReporter('test', reportDir)
    def provider1 = new ProviderInfo(name: 'provider1')
    def consumer = new ConsumerInfo(name: 'Consumer')
    def interaction1 = new RequestResponseInteraction('Interaction 1', [], new Request(), new Response())

    when:
    reporter.initialise(provider1)
    reporter.reportVerificationForConsumer(consumer, provider1, null)
    reporter.interactionDescription(interaction1)
    reporter.finaliseReport()

    def results = new File(reportDir, 'provider1.md').text

    then:
    results.contains('## Verifying a pact between _Consumer_ and _provider1_\n\nInteraction 1 ')
  }

}
