# 微信云托管 Web 服务：托管 Cocos「Web 手机端」构建产物（web/dist）
# 流水线：目标目录填 web，Dockerfile 名称 Dockerfile，端口 80
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
EXPOSE 80
