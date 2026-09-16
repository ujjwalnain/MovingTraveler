#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
javac -d "$test_dir" "$project_dir/app/src/main/java/cl/coders/movingtraveler/RouteEngine.java" "$project_dir/app/src/test/java/cl/coders/movingtraveler/RouteEngineTest.java"
java -cp "$test_dir" cl.coders.movingtraveler.RouteEngineTest
