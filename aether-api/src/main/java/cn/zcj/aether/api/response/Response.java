package cn.zcj.aether.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    // NOTE 这个是Java的序列版本号，因为这个类实现了Serializable接口，所以需要定义一个唯一的序列版本号，用于在反序列化时验证版本一致性。
    // 为什么要验证：不验证会发生什么
    //
    //  假设 Response 序列化后存进了 Redis,然后类变了:
    //
    //  存入时的类:  code(String) + info(String) + data(Object)
    //  现在的类:    code(String) + msg(String) + data(Object)
    //                          ↑ 字段改名了
    //
    //  字节流里记录的是 code | info | data 按旧结构的字节布局。如果 JVM 不做版本校验、直接按当前类结构去读：
    //
    //  - 它按新结构找 msg 这个字段——字节流里根本没有，于是读到错位的字节，或者把 info 的值填给 msg
    //  - 恢复出来的对象看起来是正常的(不是崩溃，是一个 msg="登录成功" 这种似是而非的值)，程序继续往下跑
    //  - 脏数据顺着调用链传播，最后在离病灶很远的地方爆出一个莫名其妙的 bug——这种问题排查起来是噩梦
    //
    //  反序列化的特殊性加剧了风险
    //
    //  正常创建对象要走构造函数，你可以在里面做校验、保证对象合法。但反序列化不走构造函数——JVM
    //  直接按字节流往对象内存里灌数据。这是唯一一条绕过你所有防御代码的建对象通道。如果还不验证版本，就等于对一段来历不明的字节完全敞开。
    //
    //  所以这是 fail-fast 设计
    //
    //  设计者的取舍是：
    //
    //  ▎ 与其恢复出一个错误但能用的对象，宁可抛 InvalidClassException 当场失败。
    //
    //  数据不兼容这种问题，越早暴露代价越小。沉默地产生脏数据是最坏的结局。

    //  所以那句“验证版本一致性”的真正含义是：确认写字节流的那个类版本，
    //  和你声明的“我兼容这个格式”的承诺是同一份。它不是技术限制，而是一个让你能
    //  显式管理数据格式演进合同的安全阀。

    private static final long serialVersionUID = 7000723935764546321L;

    private String code;
    private String info;
    private T data;

}
