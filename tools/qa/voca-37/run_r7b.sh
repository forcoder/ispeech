#!/usr/bin/env bash
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0 QA_APK=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3/build-78343a4/app/build/outputs/apk/debug/app-debug.apk
cd /Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3/worktree/tools/qa/voca-37
run() { n=$1; shift; echo "=== $n : $* ==="; "$@" > /tmp/r7b_$n.txt 2>&1; echo "# rc=$?" >> /tmp/r7b_$n.txt; }
run ac47 python3 ac47.py /Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3/build-78343a4/app/build/outputs/apk/debug/app-debug.apk 440
run paths2 python3 paths2.py /Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3/build-78343a4/app/build/outputs/apk/debug/app-debug.apk
run d1_fixed python3 d1regress.py /Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3/build-78343a4/app/build/outputs/apk/debug/app-debug.apk fixed
run ac9 python3 ac9.py 60 200,1000,1500,2000
run d1_base python3 d1regress.py /Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3/base-fa57a07/app/build/outputs/apk/debug/app-debug.apk base off_parity
echo ALLDONE
