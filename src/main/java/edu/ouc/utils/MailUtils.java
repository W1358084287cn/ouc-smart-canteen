package edu.ouc.utils;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Author: Sihang Xie
 * Description: 发送邮件验证码工具类
 * Date: 2022/10/22 10:31
 * Version: 0.0.1
 * Modified By:
 */
public class MailUtils {

    /**
     * 读取配置项：优先取环境变量，其次取 JVM 系统属性，最后回退到默认值。
     * 用于避免邮箱账号、SMTP 口令等敏感信息硬编码在源码中。
     */
    private static String getConfig(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(key);
        }
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    // 发送邮件验证码
    public static void sendMail(String email, String code) throws MessagingException {
        // 0.设置JDK TLS协议版本（JDK 25禁用了旧版TLS，需要显式启用TLSv1.2）
        System.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");
        System.setProperty("https.protocols", "TLSv1.2,TLSv1.3");

        // 1.创建Properties 类用于记录邮箱的一些属性
        Properties pros = new Properties();
        // 1.1 表示SMTP发送邮件，必须进行身份验证
        pros.put("mail.smtp.auth", "true");
        // 1.2 此处填写SMTP服务器
        pros.put("mail.smtp.host", "smtp.qq.com");
        // 1.3 端口号，QQ邮箱STARTTLS端口587
        pros.put("mail.smtp.port", "587");
        // 1.4 开启STARTTLS加密
        pros.put("mail.smtp.starttls.enable", "true");
        // 1.5 指定SSL协议版本为TLSv1.2（JDK 25禁用了旧版协议）
        pros.put("mail.smtp.ssl.protocols", "TLSv1.2");
        pros.put("mail.smtp.ssl.trust", "smtp.qq.com");
        // 1.5 发件人账号：优先读取环境变量 MAIL_USER，未配置时回退到本地默认值
        pros.put("mail.user", getConfig("MAIL_USER", "2771464100@qq.com"));
        // 1.6 16位SMTP授权口令：优先读取环境变量 MAIL_PASSWORD，避免密钥硬编码泄露
        pros.put("mail.password", getConfig("MAIL_PASSWORD", "qrxmfldxpctadfbc"));

        // 2.构建授权信息，用于进行SMTP进行身份验证
        Authenticator authenticator = new Authenticator() {
            protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                // 2.1 用户名
                String userName = pros.getProperty("mail.user");
                // 2.2 16位STMP口令
                String password = pros.getProperty("mail.password");
                return new javax.mail.PasswordAuthentication(userName, password);
            }
        };

        // 3.使用环境属性和授权信息，创建邮件会话
        Session mailSession = Session.getInstance(pros, authenticator);
        // 4.创建邮件消息对象
        MimeMessage message = new MimeMessage(mailSession);
        // 4.1 设置发件人
        InternetAddress from = new InternetAddress(pros.getProperty("mail.user"));
        message.setFrom(from);
        // 4.2 设置收件人
        InternetAddress to = new InternetAddress(email);
        message.setRecipient(Message.RecipientType.TO, to);
        // 4.3 设置邮件标题
        message.setSubject("【海大送餐】邮箱登录验证码");
        // 4.4 设置邮件的正文
        message.setContent("尊敬的用户：您好！\r\n您的登录验证码为：" + code + "（有效期为五分钟，请勿告知他人）", "text/html;charset=UTF-8");

        // 5.最后，发送邮件
        Transport.send(message);
    }

    // 获取六位纯数字随机验证码
    public static String getCode() {
        // 使用 java.security.SecureRandom 生成密码学安全的随机数
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        // 生成 6 位纯数字验证码（每位 0-9）
        for (int i = 0; i < 6; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}