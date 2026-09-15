# LedgerX 核心分类与账户 seed 契约 v1

- 状态：已接受；P3-001 的唯一 seed 数据来源
- 目的：保留 v3.1 StandardCategories 的分类范围、父子结构、类型、默认规则和稳定 ID，消除新 Java 实现重新生成或自行增删系统分类的歧义。
- 适用：每个 ledger.db 的 V004 core_catalog_seed migration。

## 1. 共同规则

- 所有 ID 都是已固定的小写 UUID。它们是契约值，不得按名称运行时计算；系统分类没有前端创建、编辑、归档或合并入口。
- sortOrder 是整个 seed 插入序列的零基顺序，不是每个父节点内重新编号。展示时仍遵循 API 规定的顶级优先、sortOrder、name、id 顺序。
- 类型列为 NONE 的系统分类是分组父节点，canUseForRecords 为 false。叶子分类的类型列精确限制可创建的 finance_record.recordType。
- defaultRecognitionMethod 为 STRAIGHT_LINE_DAILY 的分类，来自 v3.1 中含 FIXED_COST 类型的默认确认方式；其余叶子为 IMMEDIATE。recommendedDepreciationMethod 仅是未来资产表单提示，不替代资产 API 校验。
- 每个分类均为 isSystem=true、isLegacyCustom=false、archivedAt=null、revision=0。V004 将 createdAt 和 updatedAt 都写为执行时 UTC instant。

## 2. 系统分类

| sortOrder | id | parentId | name | recordTypes | defaultRecognitionMethod | recommendedDepreciationMethod |
| ---: | --- | --- | --- | --- | --- | --- |
| 0 | 4d320f86-3c27-811f-226a-d0443e164cc0 | null | 收入 | NONE | IMMEDIATE | null |
| 1 | d89db7bf-8d82-819e-bd38-e924e3c84c54 | 4d320f86-3c27-811f-226a-d0443e164cc0 | 工资薪酬 | INCOME | IMMEDIATE | null |
| 2 | a0d12d2c-a346-ed6c-91ce-c63805f13e35 | 4d320f86-3c27-811f-226a-d0443e164cc0 | 奖金 | INCOME | IMMEDIATE | null |
| 3 | 4ca4e7d9-8954-0702-d9f1-b4439f48660a | 4d320f86-3c27-811f-226a-d0443e164cc0 | 兼职与自由职业 | INCOME | IMMEDIATE | null |
| 4 | 0b8b497e-c475-8ead-fa34-f893dd93e7f1 | 4d320f86-3c27-811f-226a-d0443e164cc0 | 投资收益 | INCOME | IMMEDIATE | null |
| 5 | ca884af8-a258-d129-ac4f-482139080a3f | 4d320f86-3c27-811f-226a-d0443e164cc0 | 家庭支持 | INCOME | IMMEDIATE | null |
| 6 | 1bd95d4f-7a64-4061-1cd2-f996e4cb5ea2 | 4d320f86-3c27-811f-226a-d0443e164cc0 | 其他收入 | INCOME | IMMEDIATE | null |
| 7 | 50d5ed4b-a9a9-b95d-06e5-c2bc7097c233 | null | 食品与餐饮 | NONE | IMMEDIATE | null |
| 8 | 2dd8c6b0-42e3-4fff-d562-60ecac5641a1 | 50d5ed4b-a9a9-b95d-06e5-c2bc7097c233 | 食材与生鲜 | VARIABLE_COST | IMMEDIATE | null |
| 9 | ac3671d7-01f8-f2d4-3425-de279570866d | 50d5ed4b-a9a9-b95d-06e5-c2bc7097c233 | 外出餐饮 | VARIABLE_COST | IMMEDIATE | null |
| 10 | 556bd736-46a6-1567-7575-0f41daa22243 | 50d5ed4b-a9a9-b95d-06e5-c2bc7097c233 | 零食饮料 | VARIABLE_COST | IMMEDIATE | null |
| 11 | f16416a2-86ae-a12d-2cd4-ab0d3a10f0a3 | 50d5ed4b-a9a9-b95d-06e5-c2bc7097c233 | 外卖 | VARIABLE_COST | IMMEDIATE | null |
| 12 | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | null | 居住与家庭 | NONE | IMMEDIATE | null |
| 13 | cfd4f74f-09ac-22cb-37ca-c427b2973bcd | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | 房租与房贷 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 14 | 0449bb70-fecb-651c-e32c-932b6690634e | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | 物业 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 15 | c6f8729a-ff52-e5e1-1c3e-73d9061f7b23 | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | 水电燃气 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 16 | 62ea52fc-e067-fab6-3933-3a68b33fe55f | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | 家具家居 | VARIABLE_COST | IMMEDIATE | null |
| 17 | f9ea93b6-10b7-857a-fac0-c7eb7a34f009 | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | 家电 | FIXED_ASSET_PURCHASE | IMMEDIATE | null |
| 18 | 3a84e3e6-f870-e954-a284-c4060fdf7332 | 26e7cc9d-0928-cfbe-5187-13cc90d6ad9a | 日用品 | VARIABLE_COST | IMMEDIATE | null |
| 19 | 373e6d17-5823-6b4e-1d6b-7d333e9bfd56 | null | 交通与车辆 | NONE | IMMEDIATE | null |
| 20 | fecbca66-680f-3fc5-6e91-f80b301fb883 | 373e6d17-5823-6b4e-1d6b-7d333e9bfd56 | 公共交通 | VARIABLE_COST | IMMEDIATE | null |
| 21 | 750c03ee-1c21-8fb2-2e1f-430b6e72890f | 373e6d17-5823-6b4e-1d6b-7d333e9bfd56 | 打车 | VARIABLE_COST | IMMEDIATE | null |
| 22 | 4bb012a4-fde6-b01c-6f08-9fca1b8fc1c8 | 373e6d17-5823-6b4e-1d6b-7d333e9bfd56 | 燃油充电 | VARIABLE_COST | IMMEDIATE | null |
| 23 | cd101457-9cac-f205-7720-f480c5022dae | 373e6d17-5823-6b4e-1d6b-7d333e9bfd56 | 车辆购置 | FIXED_ASSET_PURCHASE | IMMEDIATE | null |
| 24 | 21f16592-c4e3-8656-5451-3891fb8f2247 | null | 数码与办公 | NONE | IMMEDIATE | null |
| 25 | dcdb71fe-e170-950d-fcc3-4b1f8bdd5160 | 21f16592-c4e3-8656-5451-3891fb8f2247 | 电脑 | FIXED_ASSET_PURCHASE | IMMEDIATE | null |
| 26 | d277ad28-1e54-fe58-f1ab-27e39c737706 | 21f16592-c4e3-8656-5451-3891fb8f2247 | 手机与平板 | FIXED_ASSET_PURCHASE | IMMEDIATE | null |
| 27 | 51ee7f96-50be-1999-6ff6-82ca095841a8 | 21f16592-c4e3-8656-5451-3891fb8f2247 | 影音摄影 | FIXED_ASSET_PURCHASE | IMMEDIATE | null |
| 28 | f949b428-b1ff-277a-5ff4-210e6dc9f35a | 21f16592-c4e3-8656-5451-3891fb8f2247 | 配件耗材 | VARIABLE_COST | IMMEDIATE | null |
| 29 | f6b0017a-b4b3-46d2-a13e-28724e20bbcb | 21f16592-c4e3-8656-5451-3891fb8f2247 | 办公设备 | FIXED_ASSET_PURCHASE | IMMEDIATE | null |
| 30 | 77be8395-254b-6934-a972-4127f7dc2135 | null | 通信与数字服务 | NONE | IMMEDIATE | null |
| 31 | 1cb1070b-8837-5b93-0513-b88c5a963500 | 77be8395-254b-6934-a972-4127f7dc2135 | 手机通信 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 32 | f7f0e5db-65aa-bc4d-eb5f-076d3f718f83 | 77be8395-254b-6934-a972-4127f7dc2135 | 网络宽带 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 33 | bb5ac161-b521-150a-2531-1e3ec43557e1 | 77be8395-254b-6934-a972-4127f7dc2135 | 软件与云服务 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 34 | 4fc261a3-efa9-bbf4-43a7-a9a43449303b | 77be8395-254b-6934-a972-4127f7dc2135 | AI 服务 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 35 | 1d588c3c-cd58-0c42-6f28-341a81a87280 | 77be8395-254b-6934-a972-4127f7dc2135 | 影音会员 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 36 | 0eca7df8-f381-6e32-8f86-88cba43a1583 | 77be8395-254b-6934-a972-4127f7dc2135 | 游戏与内容会员 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 37 | 2a801448-165d-d206-d3f1-498b938a8084 | null | 医疗与健康 | NONE | IMMEDIATE | null |
| 38 | 82af3c0f-985d-6329-edc7-8009e020bd81 | 2a801448-165d-d206-d3f1-498b938a8084 | 药品 | VARIABLE_COST | IMMEDIATE | null |
| 39 | 6c4952e7-5ac5-fefd-9b7f-cc3cdbeeb930 | 2a801448-165d-d206-d3f1-498b938a8084 | 门诊住院 | VARIABLE_COST | IMMEDIATE | null |
| 40 | 850663fa-ba33-fcee-c8d6-65c742bf67de | 2a801448-165d-d206-d3f1-498b938a8084 | 健身运动 | FIXED_COST, VARIABLE_COST | STRAIGHT_LINE_DAILY | null |
| 41 | 537b16b0-b512-f4b4-559f-459609729578 | null | 教育与成长 | NONE | IMMEDIATE | null |
| 42 | 2e0eb830-788a-186b-32cd-eea748b50560 | 537b16b0-b512-f4b4-559f-459609729578 | 书籍 | VARIABLE_COST | IMMEDIATE | null |
| 43 | a82c88c9-d47a-bf43-329d-df10a8496682 | 537b16b0-b512-f4b4-559f-459609729578 | 课程培训 | FIXED_COST, VARIABLE_COST | STRAIGHT_LINE_DAILY | null |
| 44 | b71b9d35-5299-1ba6-dc15-ef5451ba5976 | 537b16b0-b512-f4b4-559f-459609729578 | 学费 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 45 | 2de38386-a893-8830-1a1c-1f03cb08ba8d | null | 休闲与社交 | NONE | IMMEDIATE | null |
| 46 | 6027830d-9e38-946d-317f-bdac6b97fda2 | 2de38386-a893-8830-1a1c-1f03cb08ba8d | 旅行住宿 | VARIABLE_COST | IMMEDIATE | null |
| 47 | 22bbf90f-977e-4a4e-b597-418db84da7d2 | 2de38386-a893-8830-1a1c-1f03cb08ba8d | 娱乐演出 | VARIABLE_COST | IMMEDIATE | null |
| 48 | a79c9d22-457a-7a6a-7875-74e2616df03f | 2de38386-a893-8830-1a1c-1f03cb08ba8d | 兴趣爱好 | VARIABLE_COST | IMMEDIATE | null |
| 49 | 6f6b849c-faf1-6094-5825-cc0e5c0fb478 | 2de38386-a893-8830-1a1c-1f03cb08ba8d | 礼物人情 | VARIABLE_COST | IMMEDIATE | null |
| 50 | 805bcd60-11f7-834b-862e-72a015ec8e00 | 2de38386-a893-8830-1a1c-1f03cb08ba8d | 宠物 | VARIABLE_COST | IMMEDIATE | null |
| 51 | 1f0f819e-8c83-7a0e-d70d-4f1b0d0d4e09 | null | 保险与金融 | NONE | IMMEDIATE | null |
| 52 | 0852e445-b288-e228-de1f-cd7eb30a5777 | 1f0f819e-8c83-7a0e-d70d-4f1b0d0d4e09 | 保险 | FIXED_COST | STRAIGHT_LINE_DAILY | null |
| 53 | 7b793715-3619-74de-e2a3-a9a73ad5b152 | 1f0f819e-8c83-7a0e-d70d-4f1b0d0d4e09 | 利息与手续费 | VARIABLE_COST | IMMEDIATE | null |
| 54 | 97b20915-d045-cde3-308c-8a3045e6fa81 | 1f0f819e-8c83-7a0e-d70d-4f1b0d0d4e09 | 还款 | PAYABLE_PAYMENT | IMMEDIATE | null |
| 55 | ffc040fb-f329-fe94-08b8-ca9e91405642 | null | 固定资产 | NONE | IMMEDIATE | null |
| 56 | 1048e97e-6f66-50ba-6a10-9d46f2e78204 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 房屋及装修 | FIXED_ASSET_PURCHASE | IMMEDIATE | STRAIGHT_LINE |
| 57 | 2d4db5dd-64ef-a42e-8bf9-65dc661980d8 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 电脑及电子设备 | FIXED_ASSET_PURCHASE | IMMEDIATE | DOUBLE_DECLINING_BALANCE |
| 58 | cfe27b2f-f0e4-6140-6fe2-c488c2b41f58 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 手机及平板 | FIXED_ASSET_PURCHASE | IMMEDIATE | DOUBLE_DECLINING_BALANCE |
| 59 | 4ceeda85-700d-09ee-2a52-6a7e756152f3 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 家具及家电 | FIXED_ASSET_PURCHASE | IMMEDIATE | STRAIGHT_LINE |
| 60 | 1e856280-6188-9087-ea94-1d2ad718cf79 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 车辆 | FIXED_ASSET_PURCHASE | IMMEDIATE | STRAIGHT_LINE |
| 61 | 5b5c4cf8-56fa-17dd-3035-4ab9a0fb001f | ffc040fb-f329-fe94-08b8-ca9e91405642 | 机器及工具 | FIXED_ASSET_PURCHASE | IMMEDIATE | STRAIGHT_LINE |
| 62 | c211f353-a82f-ad63-7546-8193c1b12c22 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 影音摄影设备 | FIXED_ASSET_PURCHASE | IMMEDIATE | DOUBLE_DECLINING_BALANCE |
| 63 | b9ac1392-db45-5f8e-cc39-f070ee5bcd96 | ffc040fb-f329-fe94-08b8-ca9e91405642 | 其他固定资产 | FIXED_ASSET_PURCHASE | IMMEDIATE | STRAIGHT_LINE |
| 64 | a6dcbe00-1b92-ad57-d357-36ce215956bc | null | 应付款 | NONE | IMMEDIATE | null |
| 65 | 232bcf9c-69c0-bfa3-e295-e2db970c4885 | a6dcbe00-1b92-ad57-d357-36ce215956bc | 信用卡 | PAYABLE_CREATED | IMMEDIATE | null |
| 66 | c3191b88-34b2-3be8-0550-00543362a029 | a6dcbe00-1b92-ad57-d357-36ce215956bc | 消费分期 | PAYABLE_CREATED | IMMEDIATE | null |
| 67 | b677dd24-8bb5-4bbe-9274-e70f1be5c3df | a6dcbe00-1b92-ad57-d357-36ce215956bc | 贷款 | PAYABLE_CREATED | IMMEDIATE | null |
| 68 | 25cce429-8a99-4e90-9a34-d7acf541f976 | a6dcbe00-1b92-ad57-d357-36ce215956bc | 待付账单 | PAYABLE_CREATED | IMMEDIATE | null |
| 69 | 9c70133a-c20d-92c2-ea9f-cddacd904277 | null | 其他 | NONE | IMMEDIATE | null |
| 70 | 2e4eb8a6-efac-da8f-5d25-ec76c2dbd106 | 9c70133a-c20d-92c2-ea9f-cddacd904277 | 未分类 | INCOME, FIXED_COST, VARIABLE_COST, FIXED_ASSET_PURCHASE, PAYABLE_CREATED, PAYABLE_PAYMENT | STRAIGHT_LINE_DAILY | null |

## 3. 系统账户

| id | name | kind | balanceSide | openingBalance | includeInAvailableCash | isSystem | revision |
| --- | --- | --- | --- | ---: | --- | --- | ---: |
| f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901 | 现金储备 | CASH | ASSET | 0.00 | true | true | 0 |

openingOn 不是固定字符串：V004 使用执行机器的本地自然日。用户可以随后编辑系统账户的名称、期初日、期初余额和是否计入可用现金，但不可归档它；kind 仍可改为任一 AccountKind，服务端始终重新推导 balanceSide。

## 4. 兼容边界

本清单保留数据库可容纳的六种财务类型；按 ADR-010，基础版记录页面只开放 INCOME、FIXED_COST、VARIABLE_COST，因此其他类型的 seed 分类不会出现在基础记录候选中。旧版 CustomIncrease/CustomDecrease 和旧数据迁移均为后续可选能力。
