# M4-T3 STEP 3.5-A: Runtime Characterization & SystemMessage Duplication Fix

## Executive Summary

✅ **COMPLETED**: SystemMessage duplication bug identified through runtime observation and successfully fixed.

## Problem Identified

### Runtime Observation Results

**Model Call #1 (Initial):**
```
Total messages: 2
  [0] SystemMessage
  [1] UserMessage
```
✅ Correct - No duplication

**Model Call #2 (Resume - BEFORE FIX):**
```
Total messages: 5
  [0] SystemMessage  ← DUPLICATE
  [1] SystemMessage  ← DUPLICATE
  [2] UserMessage
  [3] AssistantMessage
  [4] ToolResponseMessage
```
❌ BUG CONFIRMED: SystemMessage duplicated

**Model Call #2 (Resume - AFTER FIX):**
```
Total messages: 4
  [0] SystemMessage  ✅ Single
  [1] UserMessage
  [2] AssistantMessage
  [3] ToolResponseMessage
```
✅ FIXED: No duplication

## Root Cause Analysis

### Verified Through Runtime

1. **originalRequest contains SystemMessage** ✅ VERIFIED
   ```
   suspensionState.originalRequest().prompt().getInstructions():
     [0] SystemMessage
     [1] UserMessage
   ```

2. **continueWithMessages() was adding duplicate SystemMessage**
   ```java
   // BEFORE FIX - caused duplication:
   chatClient
       .prompt()
       .system(systemInstruction)  // ← New SystemMessage
       .messages(continuationMessages)  // ← Already contains SystemMessage
   ```

### Architecture Issue

`suspensionState.originalRequest()` is a **complete protocol snapshot** that already includes:
- SystemMessage (from initial execution)
- UserMessage

When `continueWithMessages()` called `.system(systemInstruction)` again, it created a duplicate.

## Fix Implementation

### Change 1: Remove duplicate .system() call

**File:** `SpringAiToolCallingEngine.java`

**Before:**
```java
chatClient
    .prompt()
    .system(systemInstruction)
    .messages(messages)
    .advisors(...)
```

**After:**
```java
chatClient
    .prompt()
    // NOTE: Do NOT add .system() here - messages already contains SystemMessage
    // from originalRequest. Adding it again would cause duplication.
    .messages(messages)  // ← Already includes SystemMessage
    .advisors(...)
```

### Change 2: Fix Evidence restoration bug (discovered during testing)

**File:** `GovernanceToolCallingAdvisor.java`

**Issue:** `evidences.set(new ArrayList<>())` in `adviseCall()` cleared pre-set evidences.

**Fix:**
```java
// Before
evidences.set(new ArrayList<>());

// After
if (evidences.get() == null) {
  evidences.set(new ArrayList<>());
}
```

**Added method:**
```java
void initializeEvidences(List<Evidence> initialEvidences) {
  evidences.set(new ArrayList<>(initialEvidences));
}
```

**Usage in SpringAiToolCallingEngine:**
```java
var governanceAdvisor = new GovernanceToolCallingAdvisor(...);
governanceAdvisor.initializeEvidences(evidences);
```

## Verification

### Test Results

**Test:** `MessageFlowObservationTest.observeMessageFlowOnApprovalResume()`

✅ Passes - No SystemMessage duplication detected
✅ Tool executed exactly once
✅ Suspension and resume work correctly

**All existing tests:**
- arctra-runtime-react: 25 tests, 0 failures, 6 skipped
- arctra-core: 86 tests, 0 failures

✅ **No regressions**

## Items NOT Verified (Out of Scope)

⚠️ **ChatMemory + Session behavior** - Not tested in this characterization
- Test used `AgentExecutionContext.stateless()`
- Memory history duplication not observed (no session ID)

**Recommendation:** Future test should verify session + memory scenario to ensure:
- Memory history not duplicated
- ChatMemory correctly reads/writes during resume

## Files Changed

1. `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/SpringAiToolCallingEngine.java`
   - Removed duplicate `.system()` call in `continueWithMessages()`
   - Fixed evidence restoration logic

2. `arctra-runtime-react/src/main/java/cn/bitcss/arctra/runtime/react/GovernanceToolCallingAdvisor.java`
   - Changed `adviseCall()` to preserve pre-set evidences
   - Added `initializeEvidences()` method

3. `arctra-runtime-react/src/test/java/cn/bitcss/arctra/runtime/react/MessageFlowObservationTest.java`
   - New characterization test (captures actual model messages)

## Impact Assessment

### Benefits
✅ Eliminates SystemMessage duplication in resume flow
✅ Reduces token consumption (1 fewer message per resume)
✅ Correct protocol semantics
✅ Fixes evidence preservation bug

### Risk
⚠️ Low - All existing tests pass
⚠️ Change is minimal and focused
⚠️ Protocol semantics now match Spring AI expectations

## Next Steps

**Recommended:**
1. Create session + memory characterization test
2. Verify ChatMemory behavior during resume
3. Consider removing MessageFlowObservationTest after contract gate (it's a characterization test)

**M4-T3 Progress:**
- ✅ STEP 3.5-A: SystemMessage duplication fixed
- ⏭️ STEP 3.6: Production two-approval test (optional - may already pass)
- ⏭️ STEP 4: ToolCallingManager extraction (next major work)

## Conclusion

SystemMessage duplication bug **identified through runtime observation** and **successfully fixed** with minimal changes. All tests pass with no regressions.

**Status:** ✅ READY FOR CONTRACT GATE
