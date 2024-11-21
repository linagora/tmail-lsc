package org.lsc.plugins.connectors.james.beans;

import com.google.common.base.Objects;

public class QuotaSize {
	public long size;

	public QuotaSize(long size) {
		this.size = size;
	}
	
	public final boolean equals(Object other) {
		if (!(other instanceof QuotaSize)) {
			return false;
		}
		QuotaSize otherQuotaSize = (QuotaSize) other;
		return Objects.equal(this.size, otherQuotaSize.size);
	}

	public final int hashCode() {
         return Objects.hashCode(size);
	}
}
