package com.millionnaire.gateway.admin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @ConfigurationProperties(prefix="millionnaire.admin")
public class AdminPolicy {
    private String bootstrapUsername="", bootstrapPassword="";
    private int bcryptStrength=12, maxFailures=5, lockMinutes=5, sessionHours=12, ipAttempts=30, ipWindowSeconds=60;
    private List<String> trustedProxies=List.of();
    private boolean allowInMemory=false;
    public boolean isAllowInMemory(){return allowInMemory;} public void setAllowInMemory(boolean value){allowInMemory=value;}
    public String getBootstrapUsername(){return bootstrapUsername;} public void setBootstrapUsername(String v){bootstrapUsername=v;}
    public String getBootstrapPassword(){return bootstrapPassword;} public void setBootstrapPassword(String v){bootstrapPassword=v;}
    public int getBcryptStrength(){return bcryptStrength;} public void setBcryptStrength(int v){bcryptStrength=v;}
    public int getMaxFailures(){return maxFailures;} public void setMaxFailures(int v){maxFailures=v;}
    public int getLockMinutes(){return lockMinutes;} public void setLockMinutes(int v){lockMinutes=v;}
    public int getSessionHours(){return sessionHours;} public void setSessionHours(int v){sessionHours=v;}
    public int getIpAttempts(){return ipAttempts;} public void setIpAttempts(int v){ipAttempts=v;}
    public int getIpWindowSeconds(){return ipWindowSeconds;} public void setIpWindowSeconds(int v){ipWindowSeconds=v;}
    public List<String> getTrustedProxies(){return trustedProxies;} public void setTrustedProxies(List<String> v){trustedProxies=v==null?List.of():List.copyOf(v);}
    public void validate(){if(bcryptStrength<4||bcryptStrength>16||maxFailures<1||maxFailures>100||lockMinutes<1||lockMinutes>1440||sessionHours<1||sessionHours>168||ipAttempts<1||ipAttempts>1000||ipWindowSeconds<1||ipWindowSeconds>3600)throw new IllegalStateException("后台安全配置超出允许范围");}
}
