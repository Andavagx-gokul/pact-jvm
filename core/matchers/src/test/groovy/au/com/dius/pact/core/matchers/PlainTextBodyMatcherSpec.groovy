package au.com.dius.pact.core.matchers

import au.com.dius.pact.core.model.matchingrules.MatchingRulesImpl
import au.com.dius.pact.core.support.Json
import spock.lang.Specification
import spock.lang.Unroll

class PlainTextBodyMatcherSpec extends Specification {

  private PlainTextBodyMatcher matcher

  def setup() {
    matcher = new PlainTextBodyMatcher()
  }

  def 'Compares using equality if there is no matcher defined'() {
    expect:
    matcher.compareText(expected, actual, new MatchingRulesImpl()).empty == result

    where:

    expected   | actual     | result
    'expected' | 'actual'   | false
    'expected' | 'expected' | true
  }

  @Unroll
  def 'Uses the matcher if there is a matcher defined'() {
    expect:
    matcher.compareText(expected, actual, MatchingRulesImpl.fromJson(Json.INSTANCE.toJson(rules))).empty == result

    where:

    expected   | actual     | rules                                                        | result
    'expected' | 'actual'   | [body: ['$': [matchers: [[match: 'regex', regex: '\\d+']]]]] | false
    'expected' | 'actual'   | [body: ['$': [matchers: [[match: 'regex', regex: '\\w+']]]]] | true
    'expected' | '12324'    | [body: ['$': [matchers: [[match: 'integer']]]]]              | false
  }

}
