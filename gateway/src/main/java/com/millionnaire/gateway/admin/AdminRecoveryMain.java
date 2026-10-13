package com.millionnaire.gateway.admin;
import com.millionnaire.gateway.GatewayApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
/** Run explicitly with the gateway runtime classpath, never exposed as a web route. */
public final class AdminRecoveryMain {
    public static void main(String[] args){
        if(args.length!=1||!args[0].equals("--confirm-admin-recovery"))throw new IllegalArgumentException("恢复需要 --confirm-admin-recovery");
        String username=System.getenv("ADMIN_RECOVERY_USERNAME"),password=System.getenv("ADMIN_RECOVERY_PASSWORD");
        if(username==null||password==null)throw new IllegalArgumentException("需通过环境变量提供恢复账号和密码，不能将密码放在命令行参数");
        try(var context=new SpringApplicationBuilder(GatewayApplication.class).web(WebApplicationType.NONE).run()){
            context.getBean(AdminAuth.class).recover(username,password);
            System.out.println("后台账号已恢复；所有旧会话撤销，首次登录必须改密。");
        }
    }
}
