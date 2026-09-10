# 各课程完整课件文字量统计

范围：各课程的“课件”目录及其子目录中的 PPTX/PDF。排除目录外教材、往年试卷和视频；课件目录内的复习、习题课及不同教师版本按原样计入，未去重。

统计的是非空白字符数，不是 token 数或纯汉字数。PPTX 读取幻灯片文本，不含备注及图片 OCR；PDF 使用 pypdf 文本提取，无 OCR。少文本页指不足20个非空白字符，可能是图片页或封面。统计工具与后端 Tika 不完全相同，供选择课程；最终检索范围以实际入库文本为准。

这些完整课程尚未全部入库，不要把此目录与此前每课6文件的实验范围混用。字形编码无法解码的PDF排除出可读页数和文字量，不以乱码充当课件内容；原文件仍列在清单。

|课程|PPTX|PDF|可读页数|非空白字符|其中汉字|少文本页|异常文件|完整原件清单|
|---|---:|---:|---:|---:|---:|---:|---:|---|
|数据结构|0|28|1,910|274,390|58,445|252|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/数据结构.md>)|
|计算机组成原理|0|23|1,290|245,168|62,437|68|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/计算机组成原理.md>)|
|数据库系统|0|32|1,119|243,878|19,981|17|15|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/数据库系统.md>)|
|人工智能导论|13|2|1,212|204,862|87,895|41|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/人工智能导论.md>)|
|软件安全|16|0|816|153,215|80,979|87|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/软件安全.md>)|
|操作系统|18|0|997|146,657|60,916|106|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/操作系统.md>)|
|算法设计和分析|0|24|1,296|139,737|17,155|742|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/算法设计和分析.md>)|
|编译原理|68|1|976|122,492|39,394|58|1|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/编译原理.md>)|
|计算方法|6|0|325|35,052|16,837|20|0|[打开](<D:/1Learningoutput/javabackend/StudyAgent/output/course-text-inventory/计算方法.md>)|

## 仅 PPTX 比较

|课程|文件数|PPTX 文件总量 MiB|非空白字符|
|---|---:|---:|---:|
|软件安全|16|36.8|153,215|
|操作系统|18|63.0|146,657|
|人工智能导论|13|224.3|140,279|
|编译原理|68|2249.6|122,492|
|计算方法|6|122.3|35,052|

解析异常或不可读：16 份。逐文件结果保存在同目录 inventory.json。
