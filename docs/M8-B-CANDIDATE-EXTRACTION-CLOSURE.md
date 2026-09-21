# M8-B 候选提取（从成功执行中学习）完成报告

**里程碑：** M8-B 候选提取  
**状态：** ✅ **完成**  
**日期：** 2026-09-21  
**作者：** lov3r

---

## 执行摘要

M8-B 实现了从成功的 ReAct 执行中自动提取可重用过程候选的核心能力。采用白名单方法进行安全绑定捕获，生成确定性工具模式指纹，并验证可缓存性。

**关键成就：** Arctra 现在可以从成功执行中自动学习可重用过程模式，无需通过 ExecutionLedger，而是直接从执行事件中提取。

---

## 实现的组件

### 核心提取逻辑 (6 个生产文件)

1. **CandidateExtractor** - 候选提取器
   - 从 ExecutionTrace 提取 ProcedureCandidate
   - 验证可缓存性（仅幂等工具）
   - 推断参数绑定（INPUT/CONSTANT/PREVIOUS_STEP_OUTPUT）
   - 生成 ProcedureStep 序列

2. **ExecutionTraceCollector** - 执行跟踪收集器
   - 作为 ExecutionEventListener 实现
   - 监听 TOOL_EXECUTED 和 COMPLETED 事件
   - 直接收集工具调用序列（不通过 Ledger）
   - 成功完成时触发候选提取

3. **BindingCaptureValidator** - 绑定捕获验证器
   - 白名单方法：默认 NEVER_CAPTURE
   - 识别安全引用（INPUT/PREVIOUS_STEP_OUTPUT）
   - 允许的常量白名单（prod/staging/dev 等）
   - 拒绝疑似秘密参数（password/token/apiKey 等）

4. **ToolSchemaFingerprintGenerator** - 工具模式指纹生成器
   - SHA-256 哈希工具输入模式
   - Base64 URL 安全编码
   - 确定性指纹生成

5. **ExecutionTrace** - 执行跟踪记录
   - 轻量级内部表示
   - 工具调用序列 + 意图键
   - 不持久化，仅用于提取

6. **BindingCapturability** - 绑定可捕获性枚举
   - SAFE_TO_CAPTURE - 引用类型
   - CONSTANT_ALLOWED - 白名单常量
   - NEVER_CAPTURE - 默认/疑似秘密

---

## V1 安全约束

### 白名单绑定捕获

**安全优先：** 仅捕获显式安全的绑定

✅ **允许捕获：**
- INPUT 引用（如 `"serviceName"`）
- PREVIOUS_STEP_OUTPUT 引用（如 `"0.serviceId"`）
- RUNTIME_CONTEXT 引用（如 `"environment"`）
- 白名单常量（`prod`/`staging`/`dev`/`test`）

❌ **永不捕获：**
- 疑似秘密参数名称（password/token/apiKey/secret 等）
- 未知常量值（可能包含秘密或环境特定值）
- 动态生成的值

### 可缓存性验证

✅ **可缓存执行：**
- 所有工具必须是只读/幂等的
- 线性执行（无分支、循环）

❌ **不可缓存：**
- 包含非幂等工具（删除、更新、创建操作）
- 包含副作用操作

V1 使用硬编码的幂等工具白名单：
```java
getService, queryLogs, getMetrics, 
listResources, describeResource, searchDocumentation
```

未来应从工具元数据获取效果分类。

---

## 工具模式指纹

**目的：** 过程兼容性验证 - 检测工具输入 schema 的语义变化

**实现：**
```java
SHA-256(inputSchemaJson) → Base64 URL 安全编码
```

**特性：**
- 确定性：相同 schema → 相同指纹
- 敏感性：schema 改变 → 指纹改变
- V1 简化：直接哈希 JSON 字符串

**未来改进：**
- Schema 规范化（处理等价但格式不同的 schema）
- 语义版本控制（区分破坏性变更和兼容性变更）

---

## 架构决策

### 1. 直接事件捕获（不通过 Ledger）

ExecutionTraceCollector 直接监听执行事件，而不是从 ExecutionLedger 读取。

**优点：**
- 避免 Ledger 成为学习数据库
- 更轻量（仅内存跟踪）
- 实时提取（完成即可学习）

**权威分离：**
- ExecutionLedger = 历史审计投影
- ProcedureCandidateStore = 未提升候选权威
- ReusableProcedureStore = 已提升过程权威

### 2. 白名单 > 黑名单

默认拒绝捕获，仅显式允许安全绑定。

**安全边界：**
```
疑似秘密参数名 → NEVER_CAPTURE
未知常量值 → NEVER_CAPTURE
引用（非字面值）→ SAFE_TO_CAPTURE
白名单常量 → CONSTANT_ALLOWED
```

### 3. V1 简化

- 硬编码幂等工具白名单（实际应从元数据获取）
- 简化 JSON 参数解析（应使用真正的 JSON 库）
- 通用输出提取（应基于工具元数据）
- 基本引用检测（应更精确）

这些简化允许快速验证架构，未来可逐步改进。

---

## 测试覆盖

### M8-B 测试 (16 个测试，全部通过)

**CandidateExtractorTest** (4 测试)
- ✅ 从幂等执行提取候选
- ✅ 拒绝非幂等工具
- ✅ 提取多步骤过程
- ✅ 候选包含溯源信息

**BindingCaptureValidatorTest** (7 测试)
- ✅ 允许安全常量（prod/staging/dev）
- ✅ 拒绝未知常量
- ✅ 引用标记为安全捕获
- ✅ 拒绝秘密参数名称（password/apiKey/token）
- ✅ 检测 PREVIOUS_STEP_OUTPUT 引用
- ✅ 检测简单标识符引用
- ✅ 拒绝字面值

**ToolSchemaFingerprintGeneratorTest** (5 测试)
- ✅ 生成指纹
- ✅ 相同 schema → 相同指纹
- ✅ 不同 schema → 不同指纹
- ✅ 拒绝 null schema
- ✅ 拒绝空白 schema

### 构建状态

```
./mvnw clean compile -pl arctra-core -am
✅ BUILD SUCCESS

./mvnw test -pl arctra-core -Dtest="...M8-B tests..." -am
✅ Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
✅ BUILD SUCCESS
```

---

## M8-B 不包含的内容

正确延迟到未来轨道：

**M8-C（治理集成）：**
- OperationGovernanceEvaluator
- 缓存过程的治理重新评估
- 治理不变式测试

**M8-D（逐步执行）：**
- ProcedureExecutionCoordinator
- 参数绑定评估逻辑
- 输出提取实现
- CheckpointStore 集成（procedureState 字段）

**M8-E（意图解析）：**
- ProcedureResolver
- IntentRegistration
- 意图匹配逻辑
- 活动修订选择

**M8-F（验证与回退）：**
- 确定性验证谓词
- 过程失效检测
- 回退到 ReAct
- 自动失效策略

---

## 公共 API 增量

### 新增类型 (arctra-core)

**提取逻辑：**
- `CandidateExtractor`
- `ExecutionTraceCollector`

**验证器：**
- `BindingCaptureValidator`
- `ToolSchemaFingerprintGenerator`

**内部类型：**
- `ExecutionTrace` (内部记录)
- `BindingCapturability` (包私有枚举)

### 未暴露（内部实现）

- JSON 参数解析逻辑
- 幂等工具白名单
- 输出提取启发式
- 候选 ID 生成

---

## 关键设计决策

### 1. ExecutionTrace 是瞬态的

ExecutionTrace 不持久化，仅存在于内存中直到候选提取完成。

**优点：**
- 轻量级
- 无模式演化负担
- 清晰的内部 vs 公共边界

### 2. 候选提取是尽力而为的

提取失败不影响执行完成。如果提取失败：
- 执行仍然成功完成
- COMPLETED 事件仍然发出
- 无候选创建（静默失败）

### 3. 幂等性检查是保守的

V1 使用保守的白名单。如果工具不在白名单中 → 不可缓存。

**宁可错失学习机会，也不要缓存不安全的操作。**

### 4. 秘密检测是启发式的

基于参数名称模式（password/token/apiKey 等）。

**局限性：**
- 可能有假阴性（秘密但名称不明显）
- 可能有假阳性（非秘密但名称相似）

V1 偏向安全：疑似秘密 → 不捕获。

---

## 已知限制（按设计延迟）

### M8-B 范围边界

1. **无 JSON 库集成** - 使用简化的手动解析
2. **无工具元数据查询** - 幂等性硬编码
3. **无输出模式推断** - 通用 "id" 字段提取
4. **无意图键推导** - 需要显式提供
5. **无代理名称推导** - 需要显式提供
6. **无候选验证** - 延迟到 M8-F
7. **无候选提升 UI** - 延迟到 M8-E
8. **无过程执行** - 延迟到 M8-D
9. **无治理集成** - 延迟到 M8-C

---

## 文件创建

### 生产代码 (6 文件)

```
arctra-core/src/main/java/cn/bitcss/arctra/procedure/
├── BindingCapturability.java
├── BindingCaptureValidator.java
├── CandidateExtractor.java
├── ExecutionTrace.java
├── ExecutionTraceCollector.java
└── ToolSchemaFingerprintGenerator.java
```

### 测试代码 (3 文件)

```
arctra-core/src/test/java/cn/bitcss/arctra/procedure/
├── BindingCaptureValidatorTest.java
├── CandidateExtractorTest.java
└── ToolSchemaFingerprintGeneratorTest.java
```

---

## 验收标准（全部满足）

✅ 从成功执行中直接提取候选（不通过 Ledger）  
✅ 可缓存性验证（仅幂等工具）  
✅ 工具模式指纹生成（SHA-256）  
✅ 安全绑定捕获（白名单方法）  
✅ 秘密检测启发式  
✅ ExecutionTrace 表示  
✅ ExecutionEventListener 集成  
✅ 候选存储到 ProcedureCandidateStore  
✅ 专注 M8-B 测试通过（16/16）  
✅ 无 M8-A 权威更改

---

## 下一轨道建议

**M8-C：治理集成（缓存过程的治理重新评估）**

现在满足的前置条件：
- ✅ ReusableProcedure 域模型存在（M8-A）
- ✅ ProcedureCandidate 提取实现（M8-B）
- ✅ 绑定结构已定义

M8-C 将实现：
- OperationGovernanceEvaluator（内部包私有）
- 缓存过程执行前的治理重新评估
- 治理不变式：缓存永不绕过当前策略
- 确保 HITL 边界保持正确

---

## 最终架构状态

**🟢 M8-B 候选提取完成**

### 零权威更改

- ReusableProcedureStore 权威：**未更改**
- ProcedureCandidateStore 权威：**未更改**
- CheckpointStore 权威：**未更改**
- ExecutionLedger 投影：**未更改**

### 学习能力已建立

- 自动候选提取：**已实现**
- 安全绑定捕获：**已实现**
- 工具兼容性指纹：**已实现**
- 可缓存性验证：**已实现**

### 事件集成

- ExecutionEventListener 实现：**已实现**
- 直接事件捕获（不通过 Ledger）：**已实现**
- 成功完成时提取：**已实现**

---

**M8-A 建立了知识权威。M8-B 教会了 Arctra 如何学习。**

**@author lov3r**  
**日期: 2026-09-21**  
**状态: 完成**
