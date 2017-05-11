package hu.bme.mit.theta.core.expr;

import static com.google.common.base.Preconditions.checkNotNull;

import hu.bme.mit.theta.core.type.ArrayType;
import hu.bme.mit.theta.core.type.Type;
import hu.bme.mit.theta.core.utils.ExprVisitor;

public final class ArrayReadExpr<IndexType extends Type, ElemType extends Type> implements Expr<ElemType> {

	private static final int HASH_SEED = 1321;

	private static final String OPERATOR_LABEL = "Read";

	private volatile int hashCode = 0;

	private final Expr<? extends ArrayType<? super IndexType, ? extends ElemType>> array;
	private final Expr<? extends IndexType> index;

	public ArrayReadExpr(final Expr<? extends ArrayType<? super IndexType, ? extends ElemType>> array,
			final Expr<? extends IndexType> index) {

		this.array = checkNotNull(array);
		this.index = checkNotNull(index);
	}

	public Expr<? extends ArrayType<? super IndexType, ? extends ElemType>> getArray() {
		return array;
	}

	public Expr<? extends IndexType> getIndex() {
		return index;
	}

	@Override
	public ElemType getType() {
		return array.getType().getElemType();
	}

	public ArrayReadExpr<IndexType, ElemType> with(
			final Expr<? extends ArrayType<? super IndexType, ? extends ElemType>> array,
			final Expr<? extends IndexType> index) {

		if (this.array == array && this.index == index) {
			return this;
		} else {
			return Exprs.Read(array, index);
		}
	}

	public ArrayReadExpr<IndexType, ElemType> withArray(
			final Expr<? extends ArrayType<? super IndexType, ? extends ElemType>> array) {
		return with(array, getIndex());
	}

	public ArrayReadExpr<IndexType, ElemType> withIndex(final Expr<? extends IndexType> index) {
		return with(getArray(), index);
	}

	@Override
	public <P, R> R accept(final ExprVisitor<? super P, ? extends R> visitor, final P param) {
		return visitor.visit(this, param);
	}

	@Override
	public int hashCode() {
		int result = hashCode;
		if (result == 0) {
			result = HASH_SEED;
			result = 31 * result + array.hashCode();
			result = 31 * result + index.hashCode();
			hashCode = result;
		}

		return result;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		} else if (obj instanceof ArrayReadExpr<?, ?>) {
			final ArrayReadExpr<?, ?> that = (ArrayReadExpr<?, ?>) obj;
			return this.getArray().equals(that.getArray()) && this.getIndex().equals(that.getIndex());
		} else {
			return false;
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append(OPERATOR_LABEL);
		sb.append("(");
		sb.append(array);
		sb.append(", ");
		sb.append(index);
		sb.append(")");
		return sb.toString();
	}

}
