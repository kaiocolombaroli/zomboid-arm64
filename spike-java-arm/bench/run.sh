#!/bin/bash
cd "$(dirname "$0")"
A="/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/jre64/bin/java.exe"
B="../jdk-25-arm64/bin/java.exe"
OPTS="-Xms2g -Xmx2g -XX:+UseZGC"
echo "kind,jvm,round,workload,value" > results.csv
for i in 1 2 3 4 5; do
  for J in A B; do
    if [ $J = A ]; then P="$A"; else P="$B"; fi
    s=$(date +%s%N); "$P" -version >/dev/null 2>&1; e=$(date +%s%N)
    echo "startup_ms,$J,$i,java-version,$(( (e-s)/1000000 ))" >> results.csv
  done
done
for r in 1 2; do
  for J in A B; do
    if [ $J = A ]; then P="$A"; else P="$B"; fi
    "$P" $OPTS -cp . Bench 2>&1 | grep '^RES' | awk -F, -v j=$J -v r=$r '{print "ops_per_s," j "," r "-" $3 "," $2 "," $4}' >> results.csv
    echo "done $J round $r" >> progress.txt
  done
done
echo FINISHED >> progress.txt
