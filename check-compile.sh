#!/bin/bash
# M6-T5 Compile Check Script
cd "$(dirname "$0")"
mvn clean install -DskipTests -Dmaven.test.skip=true -q 2>&1 | grep -E "(BUILD SUCCESS|BUILD FAILURE|ERROR)" | head -30
