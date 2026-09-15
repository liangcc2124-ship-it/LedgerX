# Profiles REST API

- 状态：已实施
- 类型：REST
- 调用方：Vue 3 renderer
- 提供方：Java 11 本地后端 profiles-catalog 模块
- 引用：[全局 API](../api.md)、[profiles-catalog 模块](../modules/profiles-catalog.md)、[数据库](../database.md)

## 1. 公共边界与 DTO

认证、来源、请求 ID、响应 envelope、错误、日期时间、幂等、并发和重试遵循[全局 API](../api.md)。本文件只定义 profiles 特有行为。READY 状态会声明 `profiles.read` 和 `profiles.write` 能力；恢复模式下只允许读取已经安全打开的 catalog 列表（`meta.dataRevision:null`），mutation 和 operation 查询返回 `423 RECOVERY_REQUIRED`。

`ProfileSummary`：

```json
{
  "id":"8ec4ec62-d0c6-4ad8-8329-dedccba59fd4",
  "name":"默认空间",
  "status":"ACTIVE",
  "createdAt":"2026-09-12T08:30:00Z",
  "lastOpenedAt":"2026-09-12T08:30:00Z",
  "revision":0
}
```

`status` 只能是 `ACTIVE|INACTIVE|ARCHIVED`。所有 profile 路径、SQLite 文件名和 Windows 用户目录均不进入 DTO。

## 2. List profiles — `GET /api/v1/profiles`

用途：空间切换器和设置页读取 catalog。认证：桌面会话。数据范围：整个本机 catalog，这是普通业务 API 不隐含 active profile 的明确例外。

Query：

| 字段 | 类型 | 必填/默认 | 校验 |
| --- | --- | --- | --- |
| `includeArchived` | boolean | 否，`false` | 只接受小写 `true|false`；空字符串非法。 |
| `limit` | integer | 否，50 | 1–200；不接受小数、符号或空字符串。 |
| `cursor` | base64url string | 否 | 不透明；绑定 includeArchived、catalogRevision 和最后排序键。 |

固定排序：`ACTIVE` 第一；其余 INACTIVE 按 `lastOpenedAt DESC,id ASC`；包含 ARCHIVED 时放在最后并按同样键排序。成功 `200`：

```json
{
  "data": {
    "items": [],
    "activeProfileId":"8ec4ec62-d0c6-4ad8-8329-dedccba59fd4",
    "page":{"nextCursor":null,"hasMore":false,"limit":50}
  },
  "meta":{"dataRevision":0,"catalogRevision":0}
}
```

空 catalog 属于恢复错误而不是正常 `items=[]`；正常系统至少有一个 ACTIVE。游标 revision 过期或 query 指纹不符返回 `400 VALIDATION_FAILED`。无副作用，不建立 operation。

## 3. Create profile — `POST /api/v1/profiles`

Headers：`Authorization`、`X-Request-Id`、`Idempotency-Key` 必填；不使用 `If-Match`。Body：

| 字段 | 类型 | 必填/空值 | 校验/映射 |
| --- | --- | --- | --- |
| `id` | lowercase UUID | 是，不可 null | → `profile.id` 与 `Profiles/<id>`；由 Vue 生成。 |
| `name` | string | 是，不可 null | trim 后 1–100 Unicode code point；保存 trim 后值；允许重名。 |

未知普通字段按全局规则忽略；客户端不得提交 status/revision/path/时间。新建成功立即成为 active。成功 `201`，`Location:/api/v1/profiles/{id}`、`ETag:"0"`：

```json
{
  "data": {
    "profile": {
      "id":"17b65036-e5b1-4de9-b78d-068ae54ab047",
      "name":"家庭账本",
      "status":"ACTIVE",
      "createdAt":"2026-09-13T02:00:00Z",
      "lastOpenedAt":"2026-09-13T02:00:00Z",
      "revision":0
    },
    "previousActiveProfileId":"8ec4ec62-d0c6-4ad8-8329-dedccba59fd4"
  },
  "meta":{"dataRevision":0,"catalogRevision":1}
}
```

事务/副作用：安全创建并验证目标 `ledger.db`；catalog transaction 插入 profile、使旧 active revision+1、更新 pointer/catalog revision、保存 catalog operation；提交后切换 application context。ID 已存在或非空同名目录已存在为 `409 REFERENCE_CONFLICT`，`details.reason` 分别为 `PROFILE_ID_EXISTS|PROFILE_DIRECTORY_CONFLICT`。创建失败不改变旧 active。

## 4. Activate profile — `POST /api/v1/profiles/{id}/activate`

Path `id` 为 lowercase UUID。`If-Match` 必填，表示目标 profile revision；Body 必须是 `{}`，缺 body 或额外安全敏感字段返回 `400 VALIDATION_FAILED`。`Idempotency-Key` 必填。

目标 INACTIVE 时：先验证目标 ledger 的 profile ID、schema 和 quick health，再把它设为 active。成功 `200` 返回 `data={profile,previousActiveProfileId}`、新目标 ETag、目标 `dataRevision` 和新 `catalogRevision`。旧 active 与目标 revision 均加一。

目标已经 ACTIVE 且 If-Match 命中：成功 no-op，revision/dataRevision/catalogRevision 不变，但保存并可回放该 operation。目标 ARCHIVED/不存在为 `404 NOT_FOUND`；目标 ledger 缺失、路径不安全、profile ID 不匹配或 health 失败为 `409 REFERENCE_CONFLICT`，`details.reason="TARGET_PROFILE_UNAVAILABLE"`，原 active 不变。缺 If-Match 为 428，过期为 `409 REVISION_CONFLICT`。

## 5. Archive profile — `DELETE /api/v1/profiles/{id}`

Path lowercase UUID，`If-Match`、`Idempotency-Key` 必填，无 body。只允许归档 INACTIVE profile；当前 active 返回 `400 VALIDATION_FAILED`，`fieldErrors.id="不能归档当前用户空间。"`。不存在为 404，已归档为 `409 REFERENCE_CONFLICT`，revision 过期为 409。

成功 `200`，`data.profile.status="ARCHIVED"`，profile revision 与 catalogRevision 各加一，active `dataRevision` 不变。只更新 catalog 并保存 operation；不删除、移动或打开目标 ledger/backup 文件。本期没有 restore 或 purge endpoint。

## 6. 幂等、并发、超时与操作查询

- profile mutation 结果保存到 `catalog_processed_operation`；默认保留 7 天。
- 同 key/同 method/规范 path/canonical body hash 回放原 status/body/ETag/Location；响应使用当前请求的 `X-Request-Id`。
- 同 key 被 profile 或 active-ledger mutation 的不同请求使用时返回 `409 IDEMPOTENCY_CONFLICT`。
- `GET /api/v1/operations/{key}` 先检查 catalog operation，再检查当前 active ledger；同时命中视为完整性错误并拒绝 mutation。该既有全局入口在 profiles-catalog 的具体成功投影为 `200`，不重放原 HTTP 状态：

```json
{
  "data": {
    "idempotencyKey":"17b65036-e5b1-4de9-b78d-068ae54ab047",
    "status":"COMPLETED",
    "responseStatus":201,
    "result":{"profile":{"id":"..."}}
  },
  "meta":{"dataRevision":0,"catalogRevision":1}
}
```

`result` 是首次成功响应的 `data` 投影，`meta` 是首次成功响应的 `meta` 投影；找不到已提交记录返回 `404 NOT_FOUND`。此处只细化已在全局 API 定义的查询入口，未改变全局幂等或响应规则。
- create 不使用 If-Match；activate/archive 按上文使用 profile revision。所有 catalog mutation经过同一 application 排他写门。
- GET 建议 15 秒超时；mutation 30 秒。mutation 超时只能用同一 key 查询或重试，不能换 key。

## 7. 验收示例

| 输入/操作 | 期望 |
| --- | --- |
| 创建合法 profile | 201，新 profile active、独立 ledger revision0，重开保持。 |
| 创建 name=`"  家庭  "` | 保存并返回 `"家庭"`。 |
| name 空白/101 code point，ID 大写或非法 | 400，目录/catalog/operation 均不变化。 |
| 同 key 同 body 重试 create | 原 201/body/ETag，只有一个 profile/目录，revision 不重复增加。 |
| 同 key 改 name | 409 IDEMPOTENCY_CONFLICT，原 profile 不变。 |
| 激活健康 INACTIVE | 200，status/system status/重开均为目标 ID，旧数据不混入。 |
| 激活损坏或缺失 ledger | 409，原 active 仍可读写。 |
| 激活当前 profile | 200 no-op，三个 revision 不增加。 |
| 归档当前 profile | 400，文件和 catalog 不变。 |
| 归档 inactive 后重试同 key | 回放首次 200；换新 key再次归档为 409。 |
| 缺 token/错 Origin | 401/403；application/repository 零调用。 |

建议测试：profile 规则单元、V001→V002 migration、SQLite/filesystem 故障补偿、HTTP fixture、真实 Java 重开；Vue/Electron 空间切换在对应 UI Task 执行。
