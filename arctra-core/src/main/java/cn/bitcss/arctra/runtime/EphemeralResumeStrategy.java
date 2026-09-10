package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.agent.AgentResult;
import cn.bitcss.arctra.process.ContinuationSignal;
import java.util.Objects;
import java.util.function.Function;

/**
 * Ephemeral resume strategy using captured continuation function.
 *
 * <p>Package-private M4 implementation for in-memory suspension. Captures continuation as a Java
 * closure without durable checkpoint backing.
 *
 * @author lov3r
 * @since M5-T4
 */
class EphemeralResumeStrategy implements ResumeStrategy {

  private final Function<ContinuationSignal, AgentResult> continuationFunction;

  EphemeralResumeStrategy(Function<ContinuationSignal, AgentResult> continuationFunction) {
    this.continuationFunction =
        Objects.requireNonNull(continuationFunction, "continuationFunction cannot be null");
  }

  @Override
  public ResumeAttempt prepare(ContinuationSignal signal) {
    Objects.requireNonNull(signal, "signal cannot be null");
    return new EphemeralAttempt(continuationFunction, signal);
  }

    private record EphemeralAttempt(Function<ContinuationSignal, AgentResult> continuationFunction,
                                    ContinuationSignal signal) implements ResumeAttempt {

        @Override
        public AgentResult execute() {
            return continuationFunction.apply(signal);
        }
    }
}
