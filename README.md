# jenkins-atom-bind-credentials

Bind credentials — port of **credentials-binding** (227,051) as an atom.

凭据由 runner 解析（x-jenkins.secrets），原子只负责落地：变量 / 文件 / dotenv。

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
