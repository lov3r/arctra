package cn.bitcss.arctra.procedure;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of ReusableProcedureStore.
 *
 * <p><strong>M8 集成 V1：</strong>简化实现，用于验证端到端学习周期。
 *
 * <p><strong>存储语义：</strong>
 *
 * <ul>
 *   <li>按 procedureId 组织修订（Map&lt;procedureId, List&lt;ReusableProcedure&gt;&gt;）
 *   <li>每个 procedureId 可以有多个修订
 *   <li>修订按 revision 号排序（最新的在最后）
 * </ul>
 *
 * <p><strong>V1 简化：</strong>
 *
 * <ul>
 *   <li>无持久化（重启后丢失）
 *   <li>无事务支持
 *   <li>简单的 ConcurrentHashMap（并发安全但无复杂锁）
 * </ul>
 *
 * @author lov3r
 * @since M8-Integration
 */
public class InMemoryReusableProcedureStore implements ReusableProcedureStore {

  // procedureId -> List<ReusableProcedure> (按 revision 排序)
  private final Map<String, List<ReusableProcedure>> procedures = new ConcurrentHashMap<>();

  @Override
  public void createRevision(ReusableProcedure procedure) {
    Objects.requireNonNull(procedure, "procedure cannot be null");

    procedures.compute(procedure.procedureId(), (id, existing) -> {
      if (existing == null) {
        existing = new CopyOnWriteArrayList<>();
      }

      // 检查是否已存在相同修订
      boolean exists = existing.stream()
          .anyMatch(p -> p.revision() == procedure.revision());

      if (exists) {
        throw new IllegalArgumentException(
            "Procedure revision already exists: "
                + procedure.procedureId()
                + " revision "
                + procedure.revision());
      }

      existing.add(procedure);
      // 按 revision 号排序
      existing.sort((a, b) -> Integer.compare(a.revision(), b.revision()));

      return existing;
    });
  }

  @Override
  public Optional<ReusableProcedure> findRevision(String procedureId, int revision) {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");

    List<ReusableProcedure> revisions = procedures.get(procedureId);
    if (revisions == null) {
      return Optional.empty();
    }

    return revisions.stream()
        .filter(p -> p.revision() == revision)
        .findFirst();
  }

  @Override
  public List<ReusableProcedure> listRevisions(String procedureId) {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");

    List<ReusableProcedure> revisions = procedures.get(procedureId);
    if (revisions == null) {
      return List.of();
    }

    return List.copyOf(revisions);
  }

  @Override
  public List<ReusableProcedure> findByIntent(ProcedureScope scope, String intentKey) {
    Objects.requireNonNull(scope, "scope cannot be null");
    Objects.requireNonNull(intentKey, "intentKey cannot be null");

    // 查找匹配 scope 和 intentKey 的所有过程（所有修订）
    return procedures.values().stream()
        .flatMap(List::stream)
        .filter(p -> p.scope().equals(scope))
        .filter(p -> p.intentKey().equals(intentKey))
        .toList();
  }

  @Override
  public void updateStatus(String procedureId, int revision, ProcedureStatus newStatus) {
    Objects.requireNonNull(procedureId, "procedureId cannot be null");
    Objects.requireNonNull(newStatus, "newStatus cannot be null");

    procedures.computeIfPresent(procedureId, (id, revisions) -> {
      for (int i = 0; i < revisions.size(); i++) {
        ReusableProcedure proc = revisions.get(i);
        if (proc.revision() == revision) {
          // 创建新修订实例（record 是不可变的）
          ReusableProcedure updated = new ReusableProcedure(
              proc.procedureId(),
              proc.revision(),
              proc.scope(),
              proc.intentKey(),
              proc.steps(),
              newStatus, // 更新状态
              proc.createdAt(),
              proc.derivedFrom()
          );
          revisions.set(i, updated);
          break;
        }
      }
      return revisions;
    });
  }

  // 测试辅助方法

  /**
   * 查找最新修订（测试辅助方法）。
   */
  public Optional<ReusableProcedure> findLatest(String procedureId) {
    List<ReusableProcedure> revisions = procedures.get(procedureId);
    if (revisions == null || revisions.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(revisions.get(revisions.size() - 1));
  }

  /**
   * 查找所有过程（测试辅助方法）。
   */
  public List<ReusableProcedure> findAll() {
    return procedures.values().stream()
        .flatMap(List::stream)
        .toList();
  }

  /**
   * 按 scope 查找（测试辅助方法）。
   */
  public List<ReusableProcedure> findByScope(ProcedureScope scope) {
    Objects.requireNonNull(scope, "scope cannot be null");

    return procedures.values().stream()
        .flatMap(List::stream)
        .filter(p -> p.scope().equals(scope))
        .toList();
  }

  /**
   * 删除所有存储的过程（测试用）。
   */
  public void clear() {
    procedures.clear();
  }

  /**
   * 获取存储的过程总数（测试用）。
   */
  public int size() {
    return procedures.values().stream()
        .mapToInt(List::size)
        .sum();
  }
}
