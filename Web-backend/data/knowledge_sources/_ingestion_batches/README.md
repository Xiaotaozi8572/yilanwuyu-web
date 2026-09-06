# 入库批次过程证据

本目录记录每次知识库接入动作的过程证据，包括参与的 manifest、入库命令、配置摘要和验证报告。

## 命名规范
批次子目录：`YYYYMMDD_<批次号3位>_<对象>_<分区>/`

## 每个批次目录包含
- `batch_manifest.yaml`：参与的 manifest 文件列表、入库命令、配置摘要
- `ingestion_report.json`：入库脚本输出归档
- `validation_report.json`：验证脚本输出归档
