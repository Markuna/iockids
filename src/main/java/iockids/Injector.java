package iockids;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

public class Injector {

	// 已经生成的单例实例放在这里，后续注入处可以直接拿
	private Map<Class<?>, Object> singletons = Collections.synchronizedMap(new HashMap<>());

	//构造代码块，在构造方法执行前执行
	{
		singletons.put(Injector.class, this);
	}

	// 已经生成的限定器实例放在这里，可续注入处可以直接拿
	// 限定器就是在单例基础上增加一个类别，相当于多种单例，用Annotation来限定具体哪个单例
	private Map<Class<?>, Map<Annotation, Object>> qualifieds = Collections.synchronizedMap(new HashMap<>());

	// 尚未初始化的单例类放在这里
	private Map<Class<?>, Class<?>> singletonClasses = Collections.synchronizedMap(new HashMap<>());
	
	// 尚未初始化的限定类别单例类放在这里
	private Map<Class<?>, Map<Annotation, Class<?>>> qualifiedClasses = Collections.synchronizedMap(new HashMap<>());

	//======================================= 私有字段及构造函数初始化 END =================================================



	//==================================== registerQualifiedClass 操作 START ====================================================

	/**
	 * 注册指定类
	 * @param parentType 父类型
	 * @param clazz 实际类类型
	 * @param <T>
	 * @return
	 */
	public <T> Injector registerQualifiedClass(Class<?> parentType, Class<T> clazz) {
		//循环遍历注解
		for (Annotation anno : clazz.getAnnotations()) {
			//如果是Qualifier注解
			if (anno.annotationType().isAnnotationPresent(Qualifier.class)) {
				return this.registerQualifiedClass(parentType, anno, clazz);
			}
		}
		//循环无Qualifier注解，抛出异常
		throw new InjectException("class should decorated with annotation tagged by Qualifier");
	}

	/**
	 * 注册指定类（带注解）
	 * @param parentType
	 * @param anno
	 * @param clazz
	 * @param <T>
	 * @return
	 */
	public <T> Injector registerQualifiedClass(Class<?> parentType, Annotation anno, Class<T> clazz) {
		//检查
		if (!anno.annotationType().isAnnotationPresent(Qualifier.class)) {
			throw new InjectException(
					"annotation must be decorated with Qualifier " + anno.annotationType().getCanonicalName());
		}
		//限定类别单例类
		var annos = qualifiedClasses.get(parentType);
		//第一次获取为空
		if (annos == null) {
			annos = Collections.synchronizedMap(new HashMap<>());
			qualifiedClasses.put(parentType, annos);
			//此时 annos 的类型应为 Map<Annotation, Class<?>>，qualifiedClasses 的 value 类型
		}
		//annos put 操作 同时 检查annos Map 中 anno key 是否已经有旧值
		if (annos.put(anno, clazz) != null) {
			//如果有旧值则抛异常（但put操作已经执行了）
			throw new InjectException(String.format("duplicated qualifier %s with the same class %s",
					anno.annotationType().getCanonicalName(), parentType.getCanonicalName()));
		}
		return this;
	}


	//==================================== registerQualifiedClass 操作 END ====================================================

	/**
	 * 两次
	 *      injector.registerQualifiedClass(Node.class, NodeA.class);
	 * 		injector.registerQualifiedClass(Node.class, NodeB.class);
	 * 操作结果是
	 * 		qualifiedClasses 中，有 key: Node  value: Map<Annotation, Class<?>>
	 * 												 key: @Named("a")注解  value: NodeA
	 * 												 key: @Named("b")注解  value: NodeB
	 * Ps: @Named 注解里有 @Qualifier 元注解
	 */


	//======================================== getInstance 操作 START ==========================================================


	/**
	 * 获取对象
	 * @param clazz
	 * @return
	 */
	public <T> T getInstance(Class<T> clazz) {
		return createNew(clazz);
	}


	/**
	 * 新建对象
	 * @param clazz
	 * @param <T> 泛型返回，类似Object 但无需像 Object 需要强转为其他类型，可以直接用其他类型接收
	 * @return
	 */
	public <T> T createNew(Class<T> clazz) {
		return this.createNew(clazz, null);
	}

	@SuppressWarnings("unchecked")
	public <T> T createNew(Class<T> clazz, Consumer<T> consumer) {
		var o = singletons.get(clazz);
		if (o != null) {
			return (T) o;
		}

		var cons = new ArrayList<Constructor<T>>();
		// 实例 target
		T target = null;
		// 循环处理构造函数 目的：取出无参的默认构造函数
		for (var con : clazz.getDeclaredConstructors()) {
			// 默认构造期不需要Inject注解
			// 如果构造函数没有Inject注解，且参数数量大于0
			if (!con.isAnnotationPresent(Inject.class) && con.getParameterCount() > 0) {
				continue;
			}
			// 构造函数访问权限为非 public 的话（即私有） continue
			// trySetAccessible 来源 jdk9
			if (!con.trySetAccessible()) {
				continue;
			}
			// 构造方法添加进 list
			cons.add((Constructor<T>) con);
		}
		//多个构造函数 > 1
		if (cons.size() > 1) {
			throw new InjectException("dupcated constructor for injection class " + clazz.getCanonicalName());
		}
		//无构造函数
		if (cons.size() == 0) {
			throw new InjectException("no accessible constructor for injection class " + clazz.getCanonicalName());
		}

		target = createFromConstructor(cons.get(0)); // 构造器注入

		var isSingleton = clazz.isAnnotationPresent(Singleton.class);
		if (!isSingleton) {
			isSingleton = this.singletonClasses.containsKey(clazz);
		}
		if (isSingleton) {
			singletons.put(clazz, target);
		}
		//函数式接口 consumer 接受一个输入参数并且无返回的 lambda 操作
		if (consumer != null) {
			consumer.accept(target);
		}

		// 注入成员
		injectMembers(target);

		return target;
	}

	/**
	 * 执行构造函数 创建实例
	 * @param con
	 * @param <T>
	 * @return
	 */
	private <T> T createFromConstructor(Constructor<T> con) {
		var params = new Object[con.getParameterCount()];
		var i = 0;
		for (Parameter parameter : con.getParameters()) {
			//按照参数，实例化需要的参数
			var param = createFromParameter(parameter);
			if (param == null) {
				throw new InjectException(String.format("parameter should not be empty with name %s of class %s",
						parameter.getName(), con.getDeclaringClass().getCanonicalName()));
			}
			params[i++] = param;
		}
		try {
			return con.newInstance(params);
		} catch (Exception e) {
			throw new InjectException("create instance from constructor error", e);
		}
	}


	/**
	 * 注入成员
	 * @param t
	 */
	public <T> void injectMembers(T t) {
		List<Field> fields = new ArrayList<>();
		// 循环声明的字段
		for (Field field : t.getClass().getDeclaredFields()) {
			// 如果是Inject注解 且 访问权限可访问  （这里就是Root里的Node a b）
			if (field.isAnnotationPresent(Inject.class) && field.trySetAccessible()) {
				fields.add(field);
			}
		}
		for (Field field : fields) {
			Object f = createFromField(field);// （创建Root里的Node a b）
			try {
				field.set(t, f);
			} catch (Exception e) {
				throw new InjectException(
						String.format("set field for %s@%s error", t.getClass().getCanonicalName(), field.getName()),
						e);
			}
		}
	}

	/**
	 * 创建字段
	 * @param field
	 * @param <T>
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private <T> T createFromField(Field field) {
		var clazz = field.getType();
		T t = createFromQualified(field.getDeclaringClass(), clazz, field.getAnnotations());
		if (t != null) {
			return t;
		}
		return (T) createNew(clazz);
	}


	/**
	 * 从参数创建
	 * @param parameter
	 * @param <T>
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private <T> T createFromParameter(Parameter parameter) {
		var clazz = parameter.getType();
		T t = createFromQualified(parameter.getDeclaringExecutable().getDeclaringClass(), clazz,
				parameter.getAnnotations());
		if (t != null) {
			return t;
		}
		return (T) createNew(clazz);
	}


	/**
	 * 按照注入创建实例
	 * @param declaringClazz 声明的类型（参数或字段所在类的类类型）
	 * @param clazz 类类型（参数或字段的类型）
	 * @param annos 注解
	 * @param <T>
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private <T> T createFromQualified(Class<?> declaringClazz, Class<?> clazz, Annotation[] annos) {
		var qs = qualifieds.get(clazz);
		if (qs != null) {
			Set<Object> os = new HashSet<>();
			for (var anno : annos) {
				var o = qs.get(anno);
				if (o != null) {
					os.add(o);
				}
			}

			//有多个 Qualifier 注解
			if (os.size() > 1) {
				throw new InjectException(String.format("duplicated qualified object for field %s@%s",
						clazz.getCanonicalName(), declaringClazz.getCanonicalName()));
			}
			//有就直接返回
			if (!os.isEmpty()) {
				return (T) (os.iterator().next());
			}
		}

		var qz = qualifiedClasses.get(clazz);
		if (qz != null) {
			Set<Class<?>> oz = new HashSet<>();
			Annotation annoz = null;
			for (var anno : annos) {
				var z = qz.get(anno);
				if (z != null) {
					oz.add(z);
					annoz = anno;
				}
			}

			if (oz.size() > 1) {
				throw new InjectException(String.format("duplicated qualified classes for field %s@%s",
						clazz.getCanonicalName(), declaringClazz.getCanonicalName()));
			}
			if (!oz.isEmpty()) {
				final var annozRead = annoz;
				var t = (T) createNew(oz.iterator().next(), (o) -> {
					this.registerQualified((Class<T>) clazz, annozRead, (T) o);
				});
				return t;
			}
		}
		return null;
	}


	/**
	 * 注册
	 * @param clazz
	 * @param anno
	 * @param o
	 * @param <T>
	 * @return
	 */
	public <T> Injector registerQualified(Class<T> clazz, Annotation anno, T o) {
		if (!anno.annotationType().isAnnotationPresent(Qualifier.class)) {
			throw new InjectException(
					"annotation must be decorated with Qualifier " + anno.annotationType().getCanonicalName());
		}
		var os = qualifieds.get(clazz);
		if (os == null) {
			os = Collections.synchronizedMap(new HashMap<>());
			qualifieds.put(clazz, os);
		}
		if (os.put(anno, o) != null) {
			throw new InjectException(
					String.format("duplicated qualified object with the same qualifier %s with the class %s",
							anno.annotationType().getCanonicalName(), clazz.getCanonicalName()));
		}
		return this;
	}







	//region 未使用代码块
	/*

	public <T> Injector registerSingleton(Class<T> clazz, T o) {
		if (singletons.put(clazz, o) != null) {
			throw new InjectException("duplicated singleton object for the same class " + clazz.getCanonicalName());
		}
		return this;
	}


	public <T> Injector registerSingletonClass(Class<T> clazz) {
		return this.registerSingletonClass(clazz, clazz);
	}

	public <T> Injector registerSingletonClass(Class<?> parentType, Class<T> clazz) {
		if (singletonClasses.put(parentType, clazz) != null) {
			throw new InjectException("duplicated singleton class " + parentType.getCanonicalName());
		}
		return this;
	}

	*/

	//endregion

}
