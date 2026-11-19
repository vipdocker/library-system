#!/bin/bash
# acc-api.sh — API test helper. Usage: source or call functions with BASE/jar args.
# login <jar> <username> <password>  -> echoes body+status
acc_csrf() { # jar -> echoes token (preserve session cookies)
  curl -s -b "$1" -c "$1" -o /dev/null "http://localhost:18095/api/auth/csrf"
  grep XSRF "$1" | awk '{print $NF}'
}
acc_login() { # jar username password
  local jar="$1" t; t=$(acc_csrf "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $t" -H "Content-Type: application/json" \
    -X POST "http://localhost:18095/api/auth/login" -d "{\"username\":\"$2\",\"password\":\"$3\"}"
}
acc_post() { # jar path json  (fresh CSRF each time)
  local jar="$1" t; t=$(acc_csrf "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $t" -H "Content-Type: application/json" \
    -X POST "http://localhost:18095$2" -d "$3"
}
acc_put() { # jar path json
  local jar="$1" t; t=$(acc_csrf "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $t" -H "Content-Type: application/json" \
    -X PUT "http://localhost:18095$2" -d "$3"
}
acc_patch() { # jar path json
  local jar="$1" t; t=$(acc_csrf "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $t" -H "Content-Type: application/json" \
    -X PATCH "http://localhost:18095$2" -d "$3"
}
acc_del() { # jar path
  local jar="$1" t; t=$(acc_csrf "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $t" -X DELETE "http://localhost:18095$2"
}
acc_get() { # jar path
  curl -s -b "$1" "http://localhost:18095$2"
}
# with status code: append -w '\nstatus=%{http_code}' manually when needed
