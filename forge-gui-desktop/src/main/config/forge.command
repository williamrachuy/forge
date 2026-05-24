#!/bin/sh
cd $(dirname "${0}")
unset _JAVA_OPTIONS
export ULTRON_LEARNING_DIR="${HOME}/.forge/ultron-learning"
export ULTRON_TRACE_DIR="${ULTRON_LEARNING_DIR}/trace"
java $mandatory.java.args$ -jar $project.build.finalName$
