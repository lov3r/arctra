package cn.bitcss.arctra.runtime.react.durable;

import java.util.UUID;

/**
 * JVM/process execution incarnation identity for restart detection.
 *
 * <p><strong>M6-T4F: Automatic Recovery Mode Selection</strong>
 *
 * <p>Provides stable execution incarnation identity scoped to the application ClassLoader
 * lifetime. Used to distinguish same-incarnation resume (normal) from cross-incarnation resume
 * (recovery classification required).
 *
 * <h2>Scope</h2>
 *
 * <p>ClassLoader-scoped static singleton. All {@link SpringAiToolCallingEngine} instances loaded
 * by the same ClassLoader share the same incarnation.
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>Generated once when this class is first loaded by ClassLoader
 *   <li>Stable for entire ClassLoader lifetime
 *   <li>Regenerated on JVM restart or ClassLoader recreation
 * </ul>
 *
 * <h2>Supported Topology</h2>
 *
 * <ul>
 *   <li>Single-process deployment
 *   <li>Single application ClassLoader
 *   <li>Stable application lifecycle (no hot-reload)
 *   <li>Standard Spring Boot / standalone Java applications
 * </ul>
 *
 * <h2>Unsupported Topology</h2>
 *
 * <ul>
 *   <li>Multi-node deployments (requires claim/lease — future milestone)
 *   <li>Multiple isolated ClassLoaders (OSGi, complex app servers)
 *   <li>Hot-reload scenarios that recreate Arctra classes
 *   <li>Dynamic module systems with per-module ClassLoaders
 * </ul>
 *
 * <h2>Java-Public Internal Contract</h2>
 *
 * <p>Public for cross-package access within runtime-react only. NOT framework public API.
 *
 * @author lov3r
 * @since M6-T4F
 */
public final class ExecutionIncarnation {

  /**
   * ClassLoader-scoped execution incarnation UUID.
   *
   * <p>Initialized once on class load, immutable thereafter.
   */
  private static final String INSTANCE = UUID.randomUUID().toString();

  /**
   * Get current execution incarnation identity.
   *
   * <p>Returns the same UUID for all callers within the same ClassLoader lifetime.
   *
   * @return execution incarnation UUID (stable for ClassLoader lifetime)
   */
  public static String current() {
    return INSTANCE;
  }

  /** No instantiation. */
  private ExecutionIncarnation() {}
}
