package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.AgentProcess;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.function.Function;

/**
 * Factory for creating AgentProcess instances.
 *
 * <p>Package-visible factory that provides access to process creation for internal use.
 *
 * @author lov3r
 * @since M4
 */
public class ProcessFactory {

  private ProcessFactory() {
    // Utility class
  }

  /**
   * Create a new suspended process with continuation function.
   *
   * @param continuationFunction function to execute on resume
   * @return new AgentProcess in WAITING state
   */
  public static AgentProcess createSuspended(
      Function<ContinuationSignal, AgentResult> continuationFunction) {
    return new DefaultAgentProcess(continuationFunction);
  }
}
