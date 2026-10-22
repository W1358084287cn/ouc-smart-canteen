(function (win) {
    // ===== 演示模式：跳过登录校验 =====
    // C 端不再要求邮箱验证码登录，未登录也可直接点餐/结算。
    // 后端 LoginCheckFilter 已同步开启 AUTO_LOGIN_ENABLED，会自动注入演示账号身份。
    win.__OUC_DEMO_MODE__ = true

    axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
    // 创建axios实例
    const service = axios.create({
        // axios中请求配置有baseURL选项，表示请求URL公共部分
        baseURL: '/',
        // 超时（手机弱网下 10s 偏短，放宽到 20s 避免"操作没反应"的假死感）
        timeout: 20000
    })
    // request拦截器
    service.interceptors.request.use(config => {
        // 是否需要设置 token
        // const isToken = (config.headers || {}).isToken === false
        // if (getToken() && !isToken) {
        //   config.headers['Authorization'] = 'Bearer ' + getToken() // 让每个请求携带自定义token 请根据实际情况自行修改
        // }
        // get请求映射params参数
        if (config.method === 'get' && config.params) {
            let url = config.url + '?';
            for (const propName of Object.keys(config.params)) {
                const value = config.params[propName];
                var part = encodeURIComponent(propName) + "=";
                if (value !== null && typeof (value) !== "undefined") {
                    if (typeof value === 'object') {
                        for (const key of Object.keys(value)) {
                            let params = propName + '[' + key + ']';
                            var subPart = encodeURIComponent(params) + "=";
                            url += subPart + encodeURIComponent(value[key]) + "&";
                        }
                    } else {
                        url += part + encodeURIComponent(value) + "&";
                    }
                }
            }
            url = url.slice(0, -1);
            config.params = {};
            config.url = url;
        }
        return config
    }, error => {
        Promise.reject(error)
    })

    // 响应拦截器
    service.interceptors.response.use(res => {
            // 性能修复：原实现在这里 console.log 整个响应体（含菜品/购物车大数组），
            // 手机端每次请求都打印一次，控制台膨胀后滚动卡顿，是"手机经常卡住"的来源之一。
            // 仅保留错误场景的调试输出。
            if (res.data && res.data.code === 0 && res.data.msg === 'NOTLOGIN') {
                // 演示模式已跳过登录校验；若后端仍返回 NOTLOGIN（如管理接口），
                // 不再强制跳转登录页，避免用户在点餐过程中被反复打断。
                if (window.__OUC_DEMO_MODE__ !== true) {
                    window.top.location.href = '/front/page/login.html'
                    return Promise.reject(new Error('NOTLOGIN'))
                }
                return res.data
            }
            return res.data
        },
        error => {
            let {message} = error;
            if (message == "Network Error") {
                message = "后端接口连接异常";
            } else if (message.includes("timeout")) {
                message = "系统接口请求超时";
            } else if (message.includes("Request failed with status code")) {
                message = "系统接口" + message.substr(message.length - 3) + "异常";
            }
            // 优先使用 vant Notify；若页面未引入 vant（如登录页），降级为原生轻提示
            if (win.vant && win.vant.Notify) {
                win.vant.Notify({
                    message: message,
                    type: 'warning',
                    duration: 5 * 1000
                })
            } else {
                let tip = document.getElementById('__xct_native_tip__');
                if (!tip) {
                    tip = document.createElement('div');
                    tip.id = '__xct_native_tip__';
                    tip.style.cssText = 'position:fixed;top:12%;left:50%;transform:translateX(-50%);'
                        + 'max-width:78%;padding:10px 18px;background:rgba(0,0,0,.78);color:#fff;'
                        + 'font-size:14px;line-height:1.5;border-radius:8px;z-index:99999;'
                        + 'text-align:center;word-break:break-all;';
                    document.body.appendChild(tip);
                }
                tip.textContent = message;
                tip.style.display = 'block';
                clearTimeout(win.__xct_native_tip_timer__);
                win.__xct_native_tip_timer__ = setTimeout(() => {
                    tip.style.display = 'none';
                }, 3000);
            }
            //window.top.location.href = '/front/page/no-wify.html'
            return Promise.reject(error)
        }
    )
    win.$axios = service
})(window);
