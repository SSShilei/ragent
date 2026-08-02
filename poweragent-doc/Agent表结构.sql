# Agent表结构

```SQL
-- mx_agentflow.knowledge definition

CREATE TABLE `knowledge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` varchar(100) DEFAULT NULL COMMENT '上层是文件夹时用到,默认为"",此值对应knowledgeId',
  `tenant_id` varchar(100) DEFAULT NULL COMMENT '租户id',
  `team_id` varchar(100) DEFAULT NULL COMMENT '团队标记的唯一字符串',
  `team_name` varchar(300) DEFAULT NULL COMMENT '团队名称',
  `user_id` varchar(100) DEFAULT NULL COMMENT '用户标记的唯一字符串',
  `user_name` varchar(100) DEFAULT NULL COMMENT '用户名称',
  `knowledge_id` varchar(100) NOT NULL COMMENT '知识库的唯一标志字符串',
  `name` varchar(300) NOT NULL COMMENT '知识库名称',
  `typing` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '类型：folder:文件夹/knowledge:知识库/websit:外部deep link/faq:FAQ知识库',
  `template_type` varchar(50) DEFAULT 'simple' COMMENT '解析入库类型（advanced画布高级编排；simple页面简易配置）',
  `inited` int NOT NULL DEFAULT '0' COMMENT '初始化状态：0:待初始化 /1: 已就绪，默认0',
  `avatar` varchar(300) DEFAULT NULL COMMENT '图标地址',
  `vector_model` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '向量模型短名',
  `agent_model` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'agent模型短名',
  `permission` int NOT NULL DEFAULT '1' COMMENT '权限：0:私有/1:团队共有，默认0',
  `expiretime` timestamp NULL DEFAULT NULL COMMENT '过期时间，预留，用于后续知识库的回收',
  `website_config` json DEFAULT NULL COMMENT '外部网站配置，格式如：{"url":"","selector":"body"}',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0:正常/1:删除 , 默认0',
  `intro` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '描述信息',
  `ctime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `utime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `updater` varchar(100) DEFAULT NULL COMMENT '更新人',
  `is_schedule` tinyint NOT NULL DEFAULT '1' COMMENT '是否是定时任务0-是，1-否',
  `single_upload_limit` int DEFAULT '5' COMMENT '单次上传文件限制配置',
  `is_allow` tinyint DEFAULT '1' COMMENT '是否允许部分数据运行成功；0-否,1-是',
  `is_limit_runtime` tinyint DEFAULT '0' COMMENT '是否设置限制运行时间 0-不限制 1-限制',
  `run_start_date` date DEFAULT NULL COMMENT '运行开始日期（YYYY-MM-DD）',
  `run_end_date` date DEFAULT NULL COMMENT '运行结束日期（YYYY-MM-DD',
  `run_start_time` time DEFAULT NULL COMMENT '运行开始时间（HH:MM）',
  `run_end_time` time DEFAULT NULL COMMENT '运行结束时间（HH:MM）',
  `timeout_action` tinyint DEFAULT '3' COMMENT '超时运行处理 1-终止任务 2-暂停任务 3-不做处理',
  `visibility` int DEFAULT '2' COMMENT '1：代表仅自己可见 2：全空间可管理 3:代表全空间可编辑 4：代表全空间可阅读  5:全空间关闭批量',
  `priority_level` tinyint DEFAULT '2' COMMENT '5:高, 4:中高, 3:中, 2:中低, 1:低 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `is_guarantee` tinyint NOT NULL DEFAULT '0' COMMENT '是否保障（0否；1是）【updt:n】【Sec:D】【STD:Enum:0|1】【Fmat:n】',
  PRIMARY KEY (`id`),
  UNIQUE KEY `knowledge_un` (`knowledge_id`),
  KEY `knowledge_name_idx` (`name`,`status`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=11013 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库表';


-- mx_agentflow.knowledge_data definition

CREATE TABLE `knowledge_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(100) DEFAULT NULL COMMENT '租户id',
  `team_id` varchar(100) DEFAULT NULL COMMENT '团队唯一标志',
  `team_name` varchar(100) DEFAULT NULL COMMENT '团队名称',
  `user_id` varchar(100) DEFAULT NULL COMMENT '用户唯一标志',
  `user_name` varchar(100) DEFAULT NULL COMMENT '用户名称',
  `knowledge_id` varchar(100) NOT NULL COMMENT '知识库唯一标志',
  `dataset_id` varchar(100) NOT NULL COMMENT '数据集唯一标志',
  `process_id` varchar(100) DEFAULT NULL,
  `data_id` varchar(100) NOT NULL COMMENT '数据唯一标志',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '问题',
  `answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `full_text_token` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '分词后的词串',
  `chunk_index` int NOT NULL DEFAULT '0' COMMENT '分块的下标，\n默认为0',
  `inited` tinyint DEFAULT '0' COMMENT '初始化状态:\n0:待初始化 /1: 已就绪，默认0',
  `default_index` tinyint NOT NULL DEFAULT '0' COMMENT '0:默认索引/1自定义索引\r\n默认为0，默认索引的时候使用q+''\\n''+a作为索引，否则去索引表查询自定义索引',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态:\n0:正常/1:删除 , 默认0',
  `intro` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '描述',
  `ctime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `utime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `source_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '来源的id,chunk_id/qa_id/summary_id/faq_id/similar_question_id',
  `typing` int DEFAULT NULL COMMENT '初始化状态:\n1:块/2: QA/3: 总结/4: FAQ;5 FAQ相似问',
  `chunk_id` varchar(100) DEFAULT NULL COMMENT '来源的id,chunk_id/qa_id/summary_id',
  `retrieval_content` text COMMENT '检索内容',
  `is_enabled` tinyint DEFAULT '0' COMMENT '是否启用、禁用 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  PRIMARY KEY (`id`),
  UNIQUE KEY `knowledge_data_un` (`data_id`),
  UNIQUE KEY `knowledge_data_set_UN` (`dataset_id`,`data_id`,`knowledge_id`),
  KEY `knowledge_data_knowledge_id_idx` (`knowledge_id`,`dataset_id`,`data_id`,`status`) USING BTREE,
  KEY `idx_source` (`source_id`),
  KEY `knowledge_data_chunk_id_IDX` (`chunk_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=10432957 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库数据表';


-- mx_agentflow.knowledge_data_index definition

CREATE TABLE `knowledge_data_index` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_id` varchar(100) NOT NULL COMMENT '知识库数据的唯一标志',
  `index_id` varchar(100) NOT NULL COMMENT '自定义索引的唯一标志',
  `content` text NOT NULL COMMENT '索引内容',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态:\n0:正常/1:删除 , 默认0',
  `intro` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '描述',
  `ctime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `utime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `typing` varchar(100) DEFAULT NULL COMMENT '类型',
  `vector_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '向量ID',
  `default_index` tinyint DEFAULT '0' COMMENT '自定义索引默认0',
  `updater` varchar(100) DEFAULT NULL COMMENT '更新人id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `knowledge_data_index_un` (`index_id`),
  KEY `knowledge_data_index_data_id_idx` (`data_id`,`status`) USING BTREE,
  KEY `knowledge_data_index_vector_id_IDX` (`vector_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6967448 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库数据索引表';


-- mx_agentflow.knowledge_data_processing definition

CREATE TABLE `knowledge_data_processing` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(100) DEFAULT NULL COMMENT '租户id',
  `team_id` varchar(100) DEFAULT NULL COMMENT '团队唯一标志',
  `team_name` varchar(100) DEFAULT NULL COMMENT '团队名称',
  `user_id` varchar(100) DEFAULT NULL COMMENT '用户唯一标记',
  `user_name` varchar(100) DEFAULT NULL COMMENT '用户名称',
  `knowledge_id` varchar(100) NOT NULL COMMENT '知识库标志',
  `dataset_id` varchar(100) NOT NULL,
  `process_id` varchar(100) NOT NULL,
  `mode` varchar(100) NOT NULL COMMENT '处理的模式：\nchunk:直接分段/qa:qa分段',
  `expiretime` timestamp NULL DEFAULT NULL COMMENT '过期时间',
  `locktime` timestamp NULL DEFAULT NULL,
  `model` varchar(100) NOT NULL COMMENT '处理的模型',
  `prompt` text COMMENT '提示词',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '问题',
  `answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '答案',
  `chunk_index` int DEFAULT '0',
  `weight` int DEFAULT '0' COMMENT '模型的权重',
  `indexes` json DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态\n0:正常/1:删除 , 默认0',
  `intro` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '描述信息',
  `ctime` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `utime` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `summary` text COMMENT '总结',
  `page` varchar(30) DEFAULT NULL COMMENT '内容的页码',
  PRIMARY KEY (`id`),
  UNIQUE KEY `knowledge_data_processing_UN` (`process_id`),
  KEY `knowledge_data_processing_knowledge_id_idx` (`knowledge_id`,`dataset_id`,`locktime`,`status`) USING BTREE,
  KEY `knowledge_data_processing_locktime_index` (`locktime` DESC),
  KEY `knowledge_data_processing_dataset_id_index` (`dataset_id`) USING BTREE,
  KEY `knowledge_data_processing_status_index` (`status`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=513378 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据处理过程表';


-- mx_agentflow.knowledge_dataset definition

CREATE TABLE `knowledge_dataset` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` varchar(100) DEFAULT NULL COMMENT '上层是文件夹时用到,默认为空，对应datasetId',
  `tenant_id` varchar(100) DEFAULT NULL COMMENT '租户id',
  `team_id` varchar(100) DEFAULT NULL COMMENT '团队唯一标记字符串',
  `team_name` varchar(100) DEFAULT NULL COMMENT '团队名称',
  `user_id` varchar(100) DEFAULT NULL COMMENT '用户唯一标记',
  `user_name` varchar(100) DEFAULT NULL COMMENT '用户名称',
  `knowledge_id` varchar(100) NOT NULL COMMENT '知识库唯一标记',
  `dataset_id` varchar(100) NOT NULL COMMENT '数据集唯一标记',
  `name` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `typing` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '类型：folder:文件夹/virtual:手动录入/file:文件导入/link:外部静态链接;faq FAQ导入',
  `template_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'simple' COMMENT '解析入库类型（advanced画布高级编排；simple页面简易配置）',
  `inited` int NOT NULL DEFAULT '0' COMMENT '初始化状态：0解析中 1完成 2超时失败 3失败 4文件上传中 5终止，默认0',
  `process_type` varchar(100) NOT NULL COMMENT '数据处理的类型：chunk:直接分段/qa:问答拆分',
  `chunk_size` int DEFAULT NULL COMMENT '分块大小',
  `chunk_splitter` varchar(100) DEFAULT NULL COMMENT '自定义分块分隔符',
  `qa_prompt` text COMMENT 'QA拆分引导词',
  `file_id` varchar(100) DEFAULT NULL COMMENT '导入文件的唯一标志',
  `raw_link` varchar(300) DEFAULT NULL COMMENT '外部静态链接的地址',
  `raw_text_length` int DEFAULT NULL COMMENT '文本的大小',
  `hash_raw_text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'sha256后的文本',
  `data_total` int DEFAULT '0',
  `metadata` json DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0:正常/1:删除 , 默认0',
  `intro` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '描述信息',
  `ctime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `utime` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `updater` varchar(100) DEFAULT NULL COMMENT '修改人',
  `process_status` json DEFAULT NULL COMMENT '处理类型的状态',
  `process_config` json DEFAULT NULL COMMENT '处理类型的配置',
  `retry_time` timestamp NULL DEFAULT NULL COMMENT '重试的时间',
  `inited_time` timestamp NULL DEFAULT NULL,
  `url` varchar(1000) DEFAULT NULL COMMENT '网址url 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..1000】',
  `selector` varchar(300) DEFAULT NULL COMMENT '选择器 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..300】',
  `is_share` tinyint DEFAULT '1' COMMENT '是否公开（1默认公开 0关闭）',
  `feishu_revision_id` varchar(100) DEFAULT NULL COMMENT '飞书版本',
  `is_enabled` tinyint DEFAULT '0' COMMENT '数据集启用,禁用 【updt:n】【Sec:D】【STD:Num:】【Fmat:n】',
  `is_remind` tinyint DEFAULT '0' COMMENT '是否发送飞书提醒【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `priority` bigint NOT NULL DEFAULT '0' COMMENT '优先级',
  `is_allow` tinyint DEFAULT '1' COMMENT '是否允许部分数据运行成功；0-否,1-是',
  `count` int DEFAULT '0' COMMENT '数据数量【updt:n】【Sec:D】【STD:Num】【Fmat:n】',
  `cron_expression` varchar(100) DEFAULT NULL COMMENT '定时更新cron表达式【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `recall_count` int DEFAULT '0' COMMENT '召回数量 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `hit_count` int DEFAULT '0' COMMENT '命中引用数量 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `priority_level` tinyint DEFAULT '2' COMMENT '5:高, 4:中高, 3:中, 2:中低, 1:低 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `metric_flag` varchar(100) DEFAULT NULL COMMENT '指标标签【updt:n】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `metric_time` datetime DEFAULT NULL COMMENT '指标时间【updt:n】【Sec:D】【STD:dttime】【Fmat:YYYY-MM-DDTHH:mm:ss】',
  `is_send` tinyint NOT NULL DEFAULT '0' COMMENT '是否发送告警（0否；1是）【updt:n】【Sec:D】【STD:Enum:0|1】【Fmat:n】',
  PRIMARY KEY (`id`),
  UNIQUE KEY `knowledge_dataset_un` (`dataset_id`),
  KEY `knowledge_dataset_name_idx` (`name`,`status`) USING BTREE,
  KEY `knowledge_dataset_knowledge_id_idx` (`knowledge_id`,`status`) USING BTREE,
  KEY `knowledge_dataset_inited_IDX` (`inited`) USING BTREE,
  KEY `idx_process_status_analysis` ((cast(json_unquote(json_extract(`process_status`,_utf8mb4'$.analysis')) as unsigned))),
  KEY `idx_process_status_chunk` ((cast(json_unquote(json_extract(`process_status`,_utf8mb4'$.chunk')) as unsigned))),
  KEY `idx_process_status_qa` ((cast(json_unquote(json_extract(`process_status`,_utf8mb4'$.qa')) as unsigned))),
  KEY `idx_process_status_summary` ((cast(json_unquote(json_extract(`process_status`,_utf8mb4'$.summary')) as unsigned)))
) ENGINE=InnoDB AUTO_INCREMENT=177846 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库数据集表';


-- mx_agentflow.knowledge_dataset_chunk definition

CREATE TABLE `knowledge_dataset_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `knowledge_id` varchar(100) NOT NULL COMMENT '知识库标志',
  `dataset_id` varchar(100) NOT NULL,
  `task_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '任务ID',
  `chunk_id` varchar(100) NOT NULL COMMENT '块ID        ',
  `third_chunk_id` varchar(100) NOT NULL COMMENT '算法返回的块ID',
  `parent_chunk_id` varchar(100) NOT NULL COMMENT '父切片的块ID',
  `chunk_index` int NOT NULL COMMENT 'chunk的索引位置',
  `page` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '分页',
  `chunk_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '解析结果文本',
  `text_for_index` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '用于embeding或bm25等索引的文本',
  `chunk_info` json NOT NULL COMMENT '分片解析信息',
  `chunk_status` tinyint NOT NULL DEFAULT '0' COMMENT '处理状态',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态\n0:正常/1:删除 , 默认0',
  `template_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'simple' COMMENT '解析入库类型（advanced画布高级编排；simple页面简易配置）',
  `ctime` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `utime` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `tenant_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户id',
  `team_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '团队唯一标志',
  `user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '用户id',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `chunk_send` tinyint NOT NULL DEFAULT '0',
  `chunk_send_qa` tinyint NOT NULL DEFAULT '0',
  `chunk_send_summary` tinyint NOT NULL DEFAULT '0',
  `chunk_send_keyword` tinyint DEFAULT '0' COMMENT 'chunk是否发送到keyword',
  `recall_count` int DEFAULT '0' COMMENT '召回数量 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `hit_count` int DEFAULT '0' COMMENT '命中引用数量 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `start_time` int DEFAULT NULL COMMENT '开始时间【updt:n】【Sec:D】【STD:Num】【Fmat:n】',
  `end_time` int DEFAULT NULL COMMENT '结束时间【updt:n】【Sec:D】【STD:Num】【Fmat:n】',
  PRIMARY KEY (`id`),
  KEY `knowledge_dataset_chunk_dataset_id_IDX` (`dataset_id`) USING BTREE,
  KEY `idx_chunk` (`chunk_id`),
  KEY `idx_task` (`task_id`),
  KEY `idx_dataset_id` (`dataset_id`),
  KEY `idx_knowledge_id` (`knowledge_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5385880 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据分片表';


-- mx_agentflow.knowledge_flow definition

CREATE TABLE `knowledge_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `flow_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '画布ID',
  `version` int NOT NULL DEFAULT '0' COMMENT '版本号',
  `knowledge_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '知识库ID',
  `parse_key` varchar(100) DEFAULT NULL COMMENT '文档解析唯一标识',
  `parse_type` tinyint DEFAULT '0' COMMENT '文件解析类型（0通用；1自定义）',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '名称',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '标题',
  `support_type` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '文件解析支持类型',
  `info` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '版本描述',
  `publish_status` tinyint DEFAULT NULL COMMENT '发布状态(0草稿；1已发布)',
  `delete_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标识（0可用；1删除）',
  `tenant_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户ID',
  `team_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '团队ID',
  `create_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '修改人',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `agent_id` varchar(100) DEFAULT NULL COMMENT '智能体id',
  `agent_version` varchar(100) DEFAULT NULL COMMENT '智能体版本',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_flow` (`flow_id`,`version`,`delete_flag`) USING BTREE,
  KEY `idx_knowledge` (`knowledge_id`) USING BTREE,
  KEY `knowledge_flow_agent_id_IDX` (`agent_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=67713 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库画布表';


-- mx_agentflow.knowledge_flow_context definition

CREATE TABLE `knowledge_flow_context` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `context_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '追踪ID',
  `knowledge_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '知识库ID',
  `dataset_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '数据集ID',
  `flow_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '画布ID',
  `scheduling_type` tinyint DEFAULT '0' COMMENT '调度类型（0离线；1在线）',
  `version` int DEFAULT NULL COMMENT '版本号',
  `status` tinyint DEFAULT NULL COMMENT '流程状态(0待处理；1成功；2失败；3超时；4处理中；5待流转 6 终止 7 暂停 8 分步执行中)',
  `global_variables` json DEFAULT NULL COMMENT '全局变量【updt:0】【Sec:D】【STD:varchar】【Fmat:anc】',
  `delete_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标识（0可用；1删除）',
  `tenant_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户ID',
  `team_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '团队ID',
  `create_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '修改人',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `agent_id` varchar(100) DEFAULT NULL COMMENT '绑定的智能体id',
  `agent_version` varchar(100) DEFAULT NULL COMMENT '绑定的智能体版本',
  `context_name` varchar(100) DEFAULT NULL COMMENT '任务名称',
  `context_type` varchar(100) DEFAULT NULL COMMENT '任务类型：DataFlow',
  `context_way` varchar(100) DEFAULT NULL COMMENT '任务触发方式：Page、API',
  `email` varchar(100) DEFAULT NULL COMMENT '用户邮箱',
  `retry_timestamp` bigint DEFAULT NULL COMMENT '重试时间戳',
  `priority_level` tinyint DEFAULT '2' COMMENT '5:高, 4:中高, 3:中, 2:中低, 1:低 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `metric_flag` varchar(100) DEFAULT NULL COMMENT '指标标签【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `metric_time` datetime DEFAULT NULL COMMENT '指标时间【updt:n】【Sec:D】【STD:dttime】【Fmat:YYYY-MM-DDTHH:mm:ss】',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_flow_context` (`context_id`) USING BTREE,
  KEY `idx_flow` (`flow_id`) USING BTREE,
  KEY `idx_dataset` (`knowledge_id`,`dataset_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=86787 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库画布追踪表';


-- mx_agentflow.knowledge_flow_context_node definition

CREATE TABLE `knowledge_flow_context_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `context_node_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '追踪节点ID',
  `knowledge_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '知识库ID',
  `dataset_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '数据集ID',
  `flow_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '画布ID',
  `version` int DEFAULT NULL COMMENT '版本号',
  `context_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '追踪ID',
  `node_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '画布节点ID',
  `node_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '画布节点类型',
  `scheduling_type` tinyint DEFAULT '0' COMMENT '调度类型（0离线；1在线）',
  `retry_num` int DEFAULT NULL COMMENT '重试次数',
  `order_index` int NOT NULL DEFAULT '0' COMMENT '追踪节点下标',
  `status` int DEFAULT NULL COMMENT '节点状态（0初始化；1已发送；2成功；3失败；4处理中)',
  `delete_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标识（0可用；1删除）',
  `tenant_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户ID',
  `team_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '团队ID',
  `create_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '修改人',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `task_id` varchar(100) DEFAULT NULL COMMENT '第三方请求id',
  `input_path` varchar(600) DEFAULT NULL COMMENT '输入文件地址',
  `result_path` varchar(600) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '输出文件地址',
  `start_time` timestamp NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` timestamp NULL DEFAULT NULL COMMENT '结束时间',
  `error_msg` text,
  `error_pages` varchar(1000) DEFAULT NULL COMMENT '失败的页码',
  `node_data` json DEFAULT NULL COMMENT '执行节点数据',
  `current_variables` json DEFAULT NULL COMMENT '当前节点变量 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..200】',
  `outputs` json DEFAULT NULL COMMENT '输出参数 【updt:n】【Sec:D】【STD:Num】【Fmat:n】',
  `data_state` int DEFAULT NULL COMMENT '节点数据状态 （0-初始化；1-成功；2-失败；3-部分成功/部分失败）',
  `execute_status` int DEFAULT NULL COMMENT '阶段执行状态 0:请求阶段处理 1:结果处理阶段',
  `file_clean_flag` int NOT NULL DEFAULT '0' COMMENT '0-未删除 1-已删除 【updt:n】【Sec:D】【STD:Enum:0|1】【Fmat:n】',
  `metric_flag` varchar(100) DEFAULT NULL COMMENT '指标标签【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `metric_time` datetime DEFAULT NULL COMMENT '指标时间【updt:n】【Sec:D】【STD:dttime】【Fmat:YYYY-MM-DDTHH:mm:ss】',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_flow_node` (`context_node_id`) USING BTREE,
  KEY `idx_flow` (`flow_id`) USING BTREE,
  KEY `idx_context` (`context_id`) USING BTREE,
  KEY `idx_knowledge` (`knowledge_id`) USING BTREE,
  KEY `idx_dataset` (`dataset_id`) USING BTREE,
  KEY `knowledge_flow_context_node_task_id_IDX` (`task_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=690915 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库画布追踪节点表';


-- mx_agentflow.knowledge_flow_node definition

CREATE TABLE `knowledge_flow_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `node_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '节点ID',
  `module_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '节点前端唯一标识',
  `knowledge_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '知识库ID',
  `flow_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '画布ID',
  `version` int DEFAULT NULL COMMENT '版本号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '节点名称',
  `node_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '节点类型',
  `inputs` json DEFAULT NULL COMMENT '入参配置',
  `outputs` json DEFAULT NULL COMMENT '出参配置',
  `position` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '节点坐标',
  `order_index` int NOT NULL DEFAULT '0' COMMENT '节点下标',
  `delete_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标识（0可用；1删除）',
  `tenant_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户ID',
  `team_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '团队ID',
  `create_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '修改人',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_flow_node` (`node_id`) USING BTREE,
  KEY `idx_knowledge` (`knowledge_id`) USING BTREE,
  KEY `idx_flow` (`flow_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=391812 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库画布节点表';


-- mx_agentflow.knowledge_task definition

CREATE TABLE `knowledge_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '任务ID',
  `business_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '业务ID',
  `business_type` tinyint DEFAULT NULL COMMENT '业务类型（0知识库ID；1对话ID）',
  `type` tinyint DEFAULT NULL COMMENT '任务类型（0实时；1调试）',
  `vector_model` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '索引模型',
  `status` tinyint DEFAULT NULL COMMENT '任务状态（0待执行；1执行中；2已完成；3失败）',
  `file_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '文件ID',
  `delete_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标识（0可用；1删除）',
  `create_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '修改人',
  `create_time` timestamp NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT NULL COMMENT '修改时间',
  `team_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '团队ID',
  `tenant_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_task` (`task_id`) USING BTREE,
  KEY `idx_business` (`business_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16025 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库任务表';


-- mx_agentflow.knowledge_user_role definition

CREATE TABLE `knowledge_user_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `user_id` varchar(100) NOT NULL COMMENT '用户id 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `knowledge_id` varchar(100) NOT NULL COMMENT '知识库id 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `role_code` int NOT NULL COMMENT '角色code：reader:4 editor:3 manager:2 owner:1 【updt:0】【Sec:D】【STD:Num:】【Fmat:anc..100】',
  `version` bigint NOT NULL DEFAULT '1' COMMENT '版本号 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `delete_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记 0未删除 1删除 【updt:0】【Sec:D】【STD:Num:】【Fmat:n】',
  `create_by` varchar(100) DEFAULT NULL COMMENT '创建人 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间 【updt:n】【Sec:D】【STD:dttime】【Fmat:YYYY-MM-DDTHH:mm:ss】',
  `update_by` varchar(100) DEFAULT NULL COMMENT '更新人 【updt:0】【Sec:D】【STD:varchar】【Fmat:anc..100】',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间 【updt:n】【Sec:D】【STD:dttime】【Fmat:YYYY-MM-DDTHH:mm:ss】',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_role_delete` (`knowledge_id`,`role_code`,`delete_flag`) COMMENT '索引：按知识库和角色查询用户 【updt:0】【Sec:D】【STD:idx】【Fmat:idx】',
  KEY `uk_user_knowledge` (`user_id`,`knowledge_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=26849 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库用户角色关联表';
```

