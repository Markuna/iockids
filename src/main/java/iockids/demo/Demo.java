package iockids.demo;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import iockids.Injector;


//节点接口
interface Node {

	String name();

}



/**
 * 单例根节点
 */
@Singleton
class Root {

	/**
	 * @Inject 注入，
	 * JSR-330 标准中的注入注解，同 Spring 的 @Autowired
	 */
	@Inject
	@Named("a")
	Node a;

	/**
	 * @Named 注解 和 @Qualifier 注解功能相同，
	 * 指定名字注入
	 */
	@Inject
	@Named("b")
	Node b;

	@Override
	public String toString() {
		return String.format("root(%s, %s)", a.name(), b.name());
	}

}


/**
 * @Singleton
 * 单例注解
 * 这个单例是在 spring 容器下的单例
 * 如果有多个容器，每个容器可以都有一个
 */
@Singleton
@Named("a")
class NodeA implements Node {

	@Inject
	Leaf leaf;

	@Inject
	@Named("b")
	Node b;

	@Override
	public String name() {
		if (b == null)
			return String.format("nodeA(%s)", leaf);
		else
			return String.format("nodeAWithB(%s)", leaf);
	}

}

/**
 * node
 * 节点
 */
@Singleton
@Named("b")
class NodeB implements Node {

	Leaf leaf;

	@Inject
	@Named("a")
	Node a;

	@Inject
	public NodeB(Leaf leaf) {
		this.leaf = leaf;
	}

	@Override
	public String name() {
		if (a == null)
			return String.format("nodeB(%s)", leaf);
		else
			return String.format("nodeBWithA(%s)", leaf);
	}

}

//叶
class Leaf {

	@Inject
	Root root;//根节点

	int index;//游标

	static int sequence;//序列

	public Leaf() {
		index = sequence++;
	}

	public String toString() {
		if (root == null)
			return "leaf" + index;
		else
			return "leafwithroot" + index;
	}

}

public class Demo {

	public static void main(String[] args) {
		var injector = new Injector();
		injector.registerQualifiedClass(Node.class, NodeA.class);
		injector.registerQualifiedClass(Node.class, NodeB.class);
		var root = injector.getInstance(Root.class);
		System.out.println(root);
	}

}
