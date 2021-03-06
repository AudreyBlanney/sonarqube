/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.es.request;

import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetRequestBuilder;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.utils.log.LogTester;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.FakeIndexDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class ProxyMultiGetRequestBuilderTest {

  @Rule
  public EsTester es = EsTester.createCustom(new FakeIndexDefinition());

  @Rule
  public LogTester logTester = new LogTester();

  @Test
  public void multi_get() {
    MultiGetRequestBuilder request = es.client().prepareMultiGet();
    request.add(new MultiGetRequest.Item(FakeIndexDefinition.INDEX, FakeIndexDefinition.TYPE, "ruleKey")
      .fetchSourceContext(FetchSourceContext.FETCH_SOURCE));
    request.get();

  }

  @Test
  public void to_string() {
    assertThat(es.client().prepareMultiGet().toString()).isEqualTo("ES multi get request");
    assertThat(es.client().prepareMultiGet().add(new MultiGetRequest.Item(FakeIndexDefinition.INDEX, null, "fake1")
      .fetchSourceContext(FetchSourceContext.FETCH_SOURCE)).toString()).isEqualTo("ES multi get request [key 'fake1', index 'fakes'],");
    assertThat(es.client().prepareMultiGet().add(new MultiGetRequest.Item(FakeIndexDefinition.INDEX, FakeIndexDefinition.TYPE, "fake1")
      .fetchSourceContext(FetchSourceContext.FETCH_SOURCE)).toString()).isEqualTo("ES multi get request [key 'fake1', index 'fakes', type 'fake'],");
  }

  @Test
  public void trace_logs() {
    logTester.setLevel(LoggerLevel.TRACE);

    MultiGetRequestBuilder request = es.client().prepareMultiGet();
    request.add(new MultiGetRequest.Item(FakeIndexDefinition.INDEX, FakeIndexDefinition.TYPE, "ruleKey")
      .fetchSourceContext(FetchSourceContext.FETCH_SOURCE));
    request.get();

    assertThat(logTester.logs(LoggerLevel.TRACE)).hasSize(1);
  }

  @Test
  public void get_with_string_timeout_is_not_yet_implemented() {
    try {
      es.client().prepareMultiGet().get("1");
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("Not yet implemented");
    }
  }

  @Test
  public void get_with_time_value_timeout_is_not_yet_implemented() {
    try {
      es.client().prepareMultiGet().get(TimeValue.timeValueMinutes(1));
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalStateException.class).hasMessage("Not yet implemented");
    }
  }

  @Test
  public void execute_should_throw_an_unsupported_operation_exception() {
    try {
      es.client().prepareMultiGet().execute();
      fail();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(UnsupportedOperationException.class).hasMessage("execute() should not be called as it's used for asynchronous");
    }
  }

}
