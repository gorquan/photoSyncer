package com.coooolfan.xiaomialbumsyncer.exception

class XiaomiAuthenticationException(val requiresVerification: Boolean = false) : IllegalStateException(
    if (requiresVerification) "小米账号需要二次验证，请先在小米云服务官方网站完成验证，再刷新授权重试"
    else "小米账号授权失效，请重新扫码更新账号凭据后重试"
)
