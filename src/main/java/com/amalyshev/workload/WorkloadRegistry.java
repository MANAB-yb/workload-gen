package com.amalyshev.workload;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Maps the CLI's workload names onto the {@link Workload} beans in the context. Names are derived
 * from the class name ({@code CpuHeavyWorkload} -> {@code cpu-heavy}), so a new workload class is
 * selectable from the CLI as soon as it is annotated as a component.
 */
@Component
public class WorkloadRegistry {

  private static final String CLASS_SUFFIX = "Workload";

  private final Map<String, Workload> byName;

  public WorkloadRegistry(List<Workload> workloads) {
    Map<String, Workload> map = new LinkedHashMap<>();
    workloads.stream()
        .sorted(Comparator.comparing(WorkloadRegistry::cliName))
        .forEach(workload -> map.put(cliName(workload), workload));
    this.byName = Collections.unmodifiableMap(map);
  }

  /** The CLI name of a workload bean, e.g. {@code connection-churn}. */
  public static String cliName(Workload workload) {
    // Spring may hand back a CGLIB proxy; getUserClass unwraps it so the name stays stable.
    return cliName(ClassUtils.getUserClass(workload.getClass()).getSimpleName());
  }

  /** The CLI name derived from a workload class name, e.g. {@code ConnectionChurnWorkload}. */
  static String cliName(String className) {
    String simple = className.substring(className.lastIndexOf('.') + 1);
    if (simple.endsWith(CLASS_SUFFIX) && simple.length() > CLASS_SUFFIX.length()) {
      simple = simple.substring(0, simple.length() - CLASS_SUFFIX.length());
    }
    return toKebabCase(simple);
  }

  /**
   * Validates step names without a context, so a typo is reported before the CLI tries to
   * connect. {@link #requireAll} then resolves the actual beans once the context is up.
   */
  public static void requireKnown(List<WorkloadStep> steps) {
    Set<String> known = discoverNames();
    for (WorkloadStep step : steps) {
      if (!known.contains(normalize(step.workload()))) {
        throw new IllegalArgumentException("Unknown workload '" + step.workload()
            + "'. Known workloads: " + String.join(", ", known));
      }
    }
  }

  /**
   * Parallel chains share one instance of each workload, and that instance holds the parameters
   * of the run currently in progress. The same workload in two chains would overwrite those
   * parameters, so reject the overlap before anything starts.
   */
  public static void requireDistinct(List<List<WorkloadStep>> chains) {
    Map<String, Integer> owner = new LinkedHashMap<>();
    for (int i = 0; i < chains.size(); i++) {
      for (WorkloadStep step : chains.get(i)) {
        String name = normalize(step.workload());
        Integer previous = owner.putIfAbsent(name, i);
        if (previous != null && previous != i) {
          throw new IllegalArgumentException("Workload '" + step.workload()
              + "' is used in chain " + (previous + 1) + " and chain " + (i + 1)
              + ". Parallel chains share one instance of each workload, so a workload can "
              + "belong to only one chain.");
        }
      }
    }
  }

  /**
   * Lists the workload names by scanning the classpath instead of by starting a context, so
   * {@code --list-workloads} works without a reachable cluster. Spring Data JDBC resolves its
   * dialect over a live connection during startup, so a context alone would need a database.
   */
  public static Set<String> discoverNames() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(Workload.class));
    Set<String> names = new TreeSet<>();
    for (BeanDefinition definition
        : scanner.findCandidateComponents(WorkloadRegistry.class.getPackageName())) {
      String className = definition.getBeanClassName();
      if (className != null) {
        names.add(cliName(className));
      }
    }
    return names;
  }

  /** Every selectable workload name, sorted, for {@code --list-workloads} and error messages. */
  public Set<String> names() {
    return byName.keySet();
  }

  public Collection<Workload> all() {
    return byName.values();
  }

  /** Resolves a user-supplied name, accepting {@code cpu_heavy} and {@code CpuHeavyWorkload} too. */
  public Workload require(String name) {
    Workload workload = byName.get(normalize(name));
    if (workload == null) {
      throw new IllegalArgumentException("Unknown workload '" + name + "'. Known workloads: "
          + String.join(", ", names()));
    }
    return workload;
  }

  /**
   * Resolves every step's workload up front, so a typo in the last step of a 90-minute chain is
   * reported before the first step starts rather than an hour into the run.
   */
  public List<Workload> requireAll(List<WorkloadStep> steps) {
    return steps.stream().map(step -> require(step.workload())).toList();
  }

  private static String normalize(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("Workload name must not be empty");
    }
    String name = toKebabCase(raw.trim()).replace('_', '-').replace(' ', '-');
    while (name.contains("--")) {
      name = name.replace("--", "-");
    }
    if (name.endsWith("-" + CLASS_SUFFIX.toLowerCase(Locale.ROOT))) {
      name = name.substring(0, name.length() - CLASS_SUFFIX.length() - 1);
    }
    return name;
  }

  private static String toKebabCase(String camel) {
    StringBuilder sb = new StringBuilder(camel.length() + 8);
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      if (Character.isUpperCase(c) && i > 0 && sb.charAt(sb.length() - 1) != '-') {
        sb.append('-');
      }
      sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
  }
}
