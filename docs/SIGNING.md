# Release 签名锁定

本项目从接管版本开始固定使用同一 Release 证书。

- Alias: `artflow-workbench`
- Certificate SHA-256: `12:0E:B3:0A:1C:C4:4B:9C:3E:BF:96:24:D5:09:B0:DF:3E:DB:9B:86:06:A6:3D:73:DF:7A:83:54:7D:5F:9B:D4`
- Keystore file SHA-256 (用于核验备份文件): `6774b6ec123b12f5d709a99e845dceef1e3f59fa488482751ec795addb16bcf1`

私钥与密码不提交到仓库。CI 缺少指定 secrets 时 Release 构建会主动失败，避免误用其他签名。

旧 APK 的原作者私钥无法从 APK 中恢复，因此首次安装自维护版本需卸载旧包。此后所有版本应保持本证书不变。
