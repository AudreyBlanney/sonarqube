/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
package org.sonar.server.computation.issue;

import com.google.common.collect.EnumMultiset;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.sonar.api.issue.Issue;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.core.issue.DefaultIssue;
import org.sonar.core.issue.IssueType;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.measure.Measure;
import org.sonar.server.computation.measure.MeasureRepository;
import org.sonar.server.computation.measure.MeasureVariations;
import org.sonar.server.computation.metric.Metric;
import org.sonar.server.computation.metric.MetricRepository;
import org.sonar.server.computation.period.Period;
import org.sonar.server.computation.period.PeriodsHolder;

import static org.sonar.api.issue.Issue.STATUS_CONFIRMED;
import static org.sonar.api.issue.Issue.STATUS_OPEN;
import static org.sonar.api.issue.Issue.STATUS_REOPENED;
import static org.sonar.api.measures.CoreMetrics.BLOCKER_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.CONFIRMED_ISSUES_KEY;
import static org.sonar.api.measures.CoreMetrics.CRITICAL_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.FALSE_POSITIVE_ISSUES_KEY;
import static org.sonar.api.measures.CoreMetrics.INFO_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.MAJOR_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.MINOR_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_BLOCKER_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_CRITICAL_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_INFO_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_MAJOR_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_MINOR_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.NEW_VIOLATIONS_KEY;
import static org.sonar.api.measures.CoreMetrics.OPEN_ISSUES_KEY;
import static org.sonar.api.measures.CoreMetrics.REOPENED_ISSUES_KEY;
import static org.sonar.api.measures.CoreMetrics.VIOLATIONS_KEY;
import static org.sonar.api.rule.Severity.BLOCKER;
import static org.sonar.api.rule.Severity.CRITICAL;
import static org.sonar.api.rule.Severity.INFO;
import static org.sonar.api.rule.Severity.MAJOR;
import static org.sonar.api.rule.Severity.MINOR;

/**
 * For each component, computes the measures related to number of issues:
 * <ul>
 * <li>unresolved issues</li>
 * <li>false-positives</li>
 * <li>open issues</li>
 * <li>issues per status (open, reopen, confirmed)</li>
 * <li>issues per severity (from info to blocker)</li>
 * <li>issues per type (code smell, bug, vulnerability)</li>
 * </ul>
 * For each value, the variation on configured periods is also computed.
 */
public class IssueCounter extends IssueVisitor {

  private static final Map<String, String> SEVERITY_TO_METRIC_KEY = ImmutableMap.of(
    BLOCKER, BLOCKER_VIOLATIONS_KEY,
    CRITICAL, CRITICAL_VIOLATIONS_KEY,
    MAJOR, MAJOR_VIOLATIONS_KEY,
    MINOR, MINOR_VIOLATIONS_KEY,
    INFO, INFO_VIOLATIONS_KEY
    );

  private static final Map<String, String> SEVERITY_TO_NEW_METRIC_KEY = ImmutableMap.of(
    BLOCKER, NEW_BLOCKER_VIOLATIONS_KEY,
    CRITICAL, NEW_CRITICAL_VIOLATIONS_KEY,
    MAJOR, NEW_MAJOR_VIOLATIONS_KEY,
    MINOR, NEW_MINOR_VIOLATIONS_KEY,
    INFO, NEW_INFO_VIOLATIONS_KEY
    );

  private static final Map<IssueType, String> TYPE_TO_METRIC_KEY = ImmutableMap.<IssueType, String>builder()
    .put(IssueType.CODE_SMELL, CoreMetrics.CODE_SMELLS_KEY)
    .put(IssueType.BUG, CoreMetrics.BUGS_KEY)
    .put(IssueType.VULNERABILITY, CoreMetrics.VULNERABILITIES_KEY)
    .build();
  private static final Map<IssueType, String> TYPE_TO_NEW_METRIC_KEY = ImmutableMap.<IssueType, String>builder()
    .put(IssueType.CODE_SMELL, CoreMetrics.NEW_CODE_SMELLS_KEY)
    .put(IssueType.BUG, CoreMetrics.NEW_BUGS_KEY)
    .put(IssueType.VULNERABILITY, CoreMetrics.NEW_VULNERABILITIES_KEY)
    .build();

  private final PeriodsHolder periodsHolder;
  private final MetricRepository metricRepository;
  private final MeasureRepository measureRepository;

  private final Map<Integer, Counters> countersByComponentRef = new HashMap<>();
  private Counters currentCounters;

  public IssueCounter(PeriodsHolder periodsHolder,
    MetricRepository metricRepository, MeasureRepository measureRepository) {
    this.periodsHolder = periodsHolder;
    this.metricRepository = metricRepository;
    this.measureRepository = measureRepository;
  }

  @Override
  public void beforeComponent(Component component) {
    // TODO optimization no need to instantiate counter if no open issues
    currentCounters = new Counters();
    countersByComponentRef.put(component.getReportAttributes().getRef(), currentCounters);

    // aggregate children counters
    for (Component child : component.getChildren()) {
      // no need to keep the children in memory. They can be garbage-collected.
      Counters childCounters = countersByComponentRef.remove(child.getReportAttributes().getRef());
      currentCounters.add(childCounters);
    }
  }

  @Override
  public void onIssue(Component component, DefaultIssue issue) {
    currentCounters.add(issue);
    for (Period period : periodsHolder.getPeriods()) {
      // Add one second to not take into account issues created during current analysis
      if (issue.creationDate().getTime() >= period.getSnapshotDate() + 1000L) {
        currentCounters.addOnPeriod(issue, period.getIndex());
      }
    }
  }

  @Override
  public void afterComponent(Component component) {
    addMeasuresBySeverity(component);
    addMeasuresByStatus(component);
    addMeasuresByType(component);
    addMeasuresByPeriod(component);
    currentCounters = null;
  }

  private void addMeasuresBySeverity(Component component) {
    for (Map.Entry<String, String> entry : SEVERITY_TO_METRIC_KEY.entrySet()) {
      String severity = entry.getKey();
      String metricKey = entry.getValue();
      addMeasure(component, metricKey, currentCounters.counter().severityBag.count(severity));
    }
  }

  private void addMeasuresByStatus(Component component) {
    addMeasure(component, VIOLATIONS_KEY, currentCounters.counter().unresolved);
    addMeasure(component, OPEN_ISSUES_KEY, currentCounters.counter().open);
    addMeasure(component, REOPENED_ISSUES_KEY, currentCounters.counter().reopened);
    addMeasure(component, CONFIRMED_ISSUES_KEY, currentCounters.counter().confirmed);
    addMeasure(component, FALSE_POSITIVE_ISSUES_KEY, currentCounters.counter().falsePositives);
  }

  private void addMeasuresByType(Component component) {
    for (Map.Entry<IssueType, String> entry : TYPE_TO_METRIC_KEY.entrySet()) {
      addMeasure(component, entry.getValue(), currentCounters.counter().typeBag.count(entry.getKey()));
    }
  }

  private void addMeasure(Component component, String metricKey, int value) {
    Metric metric = metricRepository.getByKey(metricKey);
    measureRepository.add(component, metric, Measure.newMeasureBuilder().create(value));
  }

  private void addMeasuresByPeriod(Component component) {
    if (!periodsHolder.getPeriods().isEmpty()) {
      Double[] unresolvedVariations = new Double[PeriodsHolder.MAX_NUMBER_OF_PERIODS];
      for (Period period : periodsHolder.getPeriods()) {
        unresolvedVariations[period.getIndex() - 1] = new Double(currentCounters.counterForPeriod(period.getIndex()).unresolved);
      }
      measureRepository.add(component, metricRepository.getByKey(NEW_VIOLATIONS_KEY), Measure.newMeasureBuilder()
        .setVariations(new MeasureVariations(unresolvedVariations))
        .createNoValue());

      for (Map.Entry<String, String> entry : SEVERITY_TO_NEW_METRIC_KEY.entrySet()) {
        String severity = entry.getKey();
        String metricKey = entry.getValue();
        Double[] variations = new Double[PeriodsHolder.MAX_NUMBER_OF_PERIODS];
        for (Period period : periodsHolder.getPeriods()) {
          Multiset<String> bag = currentCounters.counterForPeriod(period.getIndex()).severityBag;
          variations[period.getIndex() - 1] = (double) bag.count(severity);
        }
        Metric metric = metricRepository.getByKey(metricKey);
        measureRepository.add(component, metric, Measure.newMeasureBuilder()
          .setVariations(new MeasureVariations(variations))
          .createNoValue());
      }

      // waiting for Java 8 lambda in order to factor this loop with the previous one
      // (see call currentCounters.counterForPeriod(period.getIndex()).xxx with xxx as severityBag or typeBag)
      for (Map.Entry<IssueType, String> entry : TYPE_TO_NEW_METRIC_KEY.entrySet()) {
        IssueType type = entry.getKey();
        String metricKey = entry.getValue();
        Double[] variations = new Double[PeriodsHolder.MAX_NUMBER_OF_PERIODS];
        for (Period period : periodsHolder.getPeriods()) {
          Multiset<IssueType> bag = currentCounters.counterForPeriod(period.getIndex()).typeBag;
          variations[period.getIndex() - 1] = (double) bag.count(type);
        }
        Metric metric = metricRepository.getByKey(metricKey);
        measureRepository.add(component, metric, Measure.newMeasureBuilder()
          .setVariations(new MeasureVariations(variations))
          .createNoValue());
      }
    }
  }

  /**
   * Count issues by status, resolutions, rules and severities
   */
  private static class Counter {
    private int unresolved = 0;
    private int open = 0;
    private int reopened = 0;
    private int confirmed = 0;
    private int falsePositives = 0;
    private final Multiset<String> severityBag = HashMultiset.create();
    private final EnumMultiset<IssueType> typeBag = EnumMultiset.create(IssueType.class);

    void add(Counter counter) {
      unresolved += counter.unresolved;
      open += counter.open;
      reopened += counter.reopened;
      confirmed += counter.confirmed;
      falsePositives += counter.falsePositives;
      severityBag.addAll(counter.severityBag);
      typeBag.addAll(counter.typeBag);
    }

    void add(DefaultIssue issue) {
      if (issue.resolution() == null) {
        unresolved++;
        typeBag.add(issue.type());
        severityBag.add(issue.severity());
      } else if (Issue.RESOLUTION_FALSE_POSITIVE.equals(issue.resolution())) {
        falsePositives++;
      }
      switch (issue.status()) {
        case STATUS_OPEN:
          open++;
          break;
        case STATUS_REOPENED:
          reopened++;
          break;
        case STATUS_CONFIRMED:
          confirmed++;
          break;
        default:
          // Other statuses are ignored
      }
    }
  }

  /**
   * List of {@link Counter} for regular value and periods.
   */
  private static class Counters {
    private final Counter[] array = new Counter[1 + PeriodsHolder.MAX_NUMBER_OF_PERIODS];

    Counters() {
      array[0] = new Counter();
      for (int i = 1; i <= PeriodsHolder.MAX_NUMBER_OF_PERIODS; i++) {
        array[i] = new Counter();
      }
    }

    void add(@Nullable Counters other) {
      if (other != null) {
        for (int i = 0; i < array.length; i++) {
          array[i].add(other.array[i]);
        }
      }
    }

    void addOnPeriod(DefaultIssue issue, int periodIndex) {
      array[periodIndex].add(issue);
    }

    void add(DefaultIssue issue) {
      array[0].add(issue);
    }

    Counter counter() {
      return array[0];
    }

    Counter counterForPeriod(int periodIndex) {
      return array[periodIndex];
    }
  }
}
