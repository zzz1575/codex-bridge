# Source synchronization and upstream integration

这些工具只维护源码、分支和 Pull Request，不包含服务部署、业务数据库或线上重启命令。
实际源目录、SSH 主机名、代理和检查点放在管理员的私有配置中，示例不能直接用于真实实例。

## 日常流程

1. 在 Bridge 或 Android 的开发目录修改，完成相应验证后提交 Git。
   工作树中的草稿分支需先按项目规则合入配置的源分支，再进入自动发布。
2. 已安装的提交钩子异步通知独立同步服务。未提交的文件不会被上传。
3. Bridge 从最后已合入的源码检查点计算增量；Windows 安卓端先固定提交快照，
   校验私人配置没有进入源码，再通过 SSH 传入服务器的私有源码库。
4. 服务在独立发布副本中，对最新 `main` 三方应用增量。保留 Fork 独有文件，
   不复制整个目录覆盖，也不把开发环境的私人提交身份带到公开历史。
5. 隐私检查通过后，更新 `sync/bridge` 或 `sync/android` 分支并创建 PR。
6. `Privacy`、`Bridge`、`Android` 三项检查全部通过且分支与 `main` 同步后，自动合并。
   源码检查点只在确认 PR 已合并后推进。失败或冲突停留在 PR / 私有状态文件中。
   受管理分支的 CI 收尾任务只合并已测试的精确提交，遵守主分支保护，不使用管理员绕过。
   如果主分支在测试期间更新，则更新分支并重新测试，不能沿用旧结果。

同步到仓库不代表已上线。部署仍需按独立发布流程处理。

## 上游更新

仓库的 **Merge Upstream** 工作流每天北京时间 **09:30** 检查一次，也可以在
GitHub Actions 页面使用 **Run workflow** 立即执行。GitHub 定时运行可能延迟。

它把上游 `main` 合入 `automation/upstream` 独立分支，保留本 Fork 历史和安卓目录，
在隐私检查后创建 PR 并运行同一套完整检查。使用合并提交保留上游祖先关系。
它不会用 `ours`、`theirs` 或目录覆盖来自动解决冲突，也不会自动部署。

由 `GITHUB_TOKEN` 创建的 PR 不依赖隐式触发链：工作流显式派发
`validate-fork.yml`。主分支更新后会刷新仍待合入的同步分支，定时上游检查也会补查。
仓库需允许 Actions 创建 PR，并启用 auto-merge。

## 部署私有同步服务

需要 Linux、Python 3.10+、Git、支持 `pr update-branch` 的近期 GitHub CLI，
以及所属仓库的 GitHub 认证。维护工作流还需要 `workflow` 权限。

- 复制 `config.example.json` 到不受 Git 管理的私有位置，目录权限 `0700`、文件 `0600`。
- 为服务创建独立发布 clone；`origin` 指向自己的 Fork，不得复用业务或已部署的目录。
- 在 clone 的 `.git/source-sync-owned` 写入 `dedicated-publication-scratch`。
  该标记和源目录不同的检查是执行临时分支重置前的必要条件。
- 创建 `state_dir/state.json`，分别填写 `bridge`、`android` 的 `synced_source`。
  初始检查点必须与公开仓库中对应组件的代码一致。只填写已验证的基线，不能猜测。
- Android 私有 Git 仓库需接收 `refs/source-sync/export`，配置中的读取引用为
  `refs/heads/source`。第一次接收前应导入已核对的初始源码提交。
- 用低优先级的 systemd oneshot 服务运行 `sync_source.py --config <private-config> drain`，
  用 path 单元监听 `state_dir/requests/*.request`。限制 CPU / 内存，把源目录设为只读。
  失败不会无限重试；修复后重新请求。
- 显式设置服务用户、`HOME` 和 `GH_CONFIG_DIR`，让后台服务读取已授权的 GitHub 配置；
  交互式 SSH 授权正常并不证明后台服务的认证环境相同。不要把 token 写入仓库或单元文件。
- 可用低频 timer 每两分钟调用一次同一 `drain`，只跟踪已有待合入 PR、回收合入检查点。
  空闲组件不执行 GitHub 请求或源码扫描；已阻塞的同步不会因 timer 无限重试。
- Bridge 的 `post-commit` 和 `post-merge` 钩子调用
  `sync_source.py --config <private-config> request bridge`，配置 `ref` 指向固定的源分支。
  Windows 的两个同名钩子通过隐藏的后台 PowerShell 进程执行
  `windows_sync.ps1 -Config <private-config>`；`source_ref` 默认为 `refs/heads/main`。
  在草稿分支提交不会发布该草稿，合入源分支后会再次触发同步，包含 fast-forward 合并。
- 保留已有提交钩子；不要覆盖已有钩子的逻辑。启用前先进行一次无改动同步验证。

私有 `deny_values` / `private_json_files` 可以补充实际环境的秘密值检查。
此配置本身绝对不能提交。正则审计不能替代人工核对新增内容；例外必须先人工审查，
不能为了通过 CI 关闭隐私检查。

## 失败处理

- **冲突：**同步不会更新 `main` 或源码目录。在独立审查分支解决冲突并补充回归，
  再按实际合入结果核对检查点；不得直接把源码树覆盖到 Fork。
- **检查失败：**修复对应组件并再次提交，原 PR 会更新。检查未通过不会自动合入。
- **PR 被关闭但未合并：**停止推进检查点，必须人工核对，不会跳过这批修改。
- **未推送或认证失败：**本地 Git 提交仍保留；查看 Windows `status.json` 或 Linux
  `<component>-status.json`，修复后重新触发。不得把“本地提交成功”当作“同步完成”。
- **源历史被重写：**祖先校验失败并停止，保留原检查点；需人工迁移基线。

## 维护工具测试

```sh
python3 -m unittest discover -s tools/maintenance/tests -v
pwsh -NoProfile -File tools/maintenance/tests/test_windows_source.ps1
python3 tools/maintenance/privacy.py --base origin/main
```

工具测试使用临时仓库和合成数据，不操作真实 GitHub PR 或生产服务。
Bridge / Android 的构建顺序和验证范围仍以各自文档为准。
