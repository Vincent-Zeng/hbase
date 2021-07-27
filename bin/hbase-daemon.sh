#!/usr/bin/env bash
#
#/**
# * Copyright 2007 The Apache Software Foundation
# *
# * Licensed to the Apache Software Foundation (ASF) under one
# * or more contributor license agreements.  See the NOTICE file
# * distributed with this work for additional information
# * regarding copyright ownership.  The ASF licenses this file
# * to you under the Apache License, Version 2.0 (the
# * "License"); you may not use this file except in compliance
# * with the License.  You may obtain a copy of the License at
# *
# *     http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
# * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# * See the License for the specific language governing permissions and
# * limitations under the License.
# */
#
# Runs a Hadoop hbase command as a daemon.
#
# Environment Variables
#
#   HBASE_CONF_DIR   Alternate hbase conf dir. Default is ${HBASE_HOME}/conf.
#   HBASE_LOG_DIR    Where log files are stored.  PWD by default.
#   HBASE_PID_DIR    The pid files are stored. /tmp by default.
#   HBASE_IDENT_STRING   A string representing this instance of hadoop. $USER by default
#   HBASE_NICENESS The scheduling priority for daemons. Defaults to 0.
#
# Modelled after $HADOOP_HOME/bin/hadoop-daemon.sh

usage="Usage: hbase-daemon.sh [--config <conf-dir>]\
 (start|stop) <hbase-command> \
 <args...>"

# if no args specified, show usage
if [ $# -le 1 ]; then
  echo $usage
  exit 1
fi

bin=$(dirname "$0")
bin=$(
  cd "$bin"
  pwd
)

. "$bin"/hbase-config.sh

# get arguments
startStop=$1
shift

command=$1
shift

# zeng: 将log文件 移动为 log.$num 文件
hbase_rotate_log() {
  log=$1

  # zeng:  保留5个文件
  num=5

  if [ -n "$2" ]; then
    num=$2
  fi
  if [ -f "$log" ]; then # rotate logs
    while [ $num -gt 1 ]; do
      prev=$(expr $num - 1)
      [ -f "$log.$prev" ] && mv "$log.$prev" "$log.$num"
      num=$prev
    done
    mv "$log" "$log.$num"
  fi
}

# zeng: source hbase-env.sh
if [ -f "${HBASE_CONF_DIR}/hbase-env.sh" ]; then
  . "${HBASE_CONF_DIR}/hbase-env.sh"
fi

# zeng: 日志目录为$HBASE_HOME/logs
# get log directory
if [ "$HBASE_LOG_DIR" = "" ]; then
  export HBASE_LOG_DIR="$HBASE_HOME/logs"
fi
mkdir -p "$HBASE_LOG_DIR"

# zeng: pid目录为/tmp
if [ "$HBASE_PID_DIR" = "" ]; then
  HBASE_PID_DIR=/tmp
fi

# zeng: $USER
if [ "$HBASE_IDENT_STRING" = "" ]; then
  export HBASE_IDENT_STRING="$USER"
fi

# some variables
# zeng: hbase log file name
export HBASE_LOGFILE=hbase-$HBASE_IDENT_STRING-$command-$HOSTNAME.log
export HBASE_ROOT_LOGGER="INFO,DRFA"

# zeng: log file
log=$HBASE_LOG_DIR/hbase-$HBASE_IDENT_STRING-$command-$HOSTNAME.out

# zeng: pid file
pid=$HBASE_PID_DIR/hbase-$HBASE_IDENT_STRING-$command.pid

# Set default scheduling priority
if [ "$HBASE_NICENESS" = "" ]; then
  export HBASE_NICENESS=0
fi

case $startStop in

start)
  mkdir -p "$HBASE_PID_DIR"
  if [ -f $pid ]; then
    if kill -0 $(cat $pid) >/dev/null 2>&1; then
      echo $command running as process $(cat $pid). Stop it first.
      exit 1
    fi
  fi

  # zeng: rotate log
  hbase_rotate_log $log

  echo starting $command, logging to $log

  # zeng: 执行hbase脚本
  nohup nice -n $HBASE_NICENESS "$HBASE_HOME"/bin/hbase \
    --config "${HBASE_CONF_DIR}" \
    $command $startStop "$@" >"$log" 2>&1 </dev/null &

  # zeng: pid
  echo $! >$pid

  sleep 1

  # zeng: head log
  head "$log"
  ;;

stop)
  if [ -f $pid ]; then
    if kill -0 $(cat $pid) >/dev/null 2>&1; then
      echo -n stopping $command

      if [ "$command" = "master" ]; then
        # zeng: 执行hbase脚本
        nohup nice -n $HBASE_NICENESS "$HBASE_HOME"/bin/hbase \
          --config "${HBASE_CONF_DIR}" \
          $command $startStop "$@" >"$log" 2>&1 </dev/null &
      else
        # zeng: kill
        kill $(cat $pid) >/dev/null 2>&1
      fi

      # zeng: wait
      while kill -0 $(cat $pid) >/dev/null 2>&1; do
        echo -n "."
        sleep 1
      done

      echo
    else
      echo no $command to stop
    fi
  else
    echo no $command to stop
  fi
  ;;

*)
  echo $usage
  exit 1
  ;;

esac
