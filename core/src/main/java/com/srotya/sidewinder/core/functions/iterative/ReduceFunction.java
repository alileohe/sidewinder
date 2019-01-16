/**
 * Copyright Ambud Sharma
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.srotya.sidewinder.core.functions.iterative;

import com.srotya.sidewinder.core.storage.DataPoint;
import com.srotya.sidewinder.core.storage.DataPointIterator;

public abstract class ReduceFunction extends FunctionIterator {

	public ReduceFunction(DataPointIterator iterator, boolean isFp) {
		super(iterator, isFp);
	}

	@Override
	public DataPoint next() {
		DataPoint out = new DataPoint();
		DataPoint next = iterator.next();
		out.setTimestamp(next.getTimestamp());
		long longValue = next.getLongValue();
		double doubleValue = Double.longBitsToDouble(next.getLongValue());
		while (iterator.hasNext()) {
			next = iterator.next();
			if (isFp) {
				doubleValue = reduce(doubleValue, Double.longBitsToDouble(next.getLongValue()));
			} else {
				longValue = reduce(longValue, next.getLongValue());
			}
		}
		if (isFp) {
			longValue = Double.doubleToLongBits(doubleValue);
		}
		out.setLongValue(longValue);
		return out;
	}

	protected abstract double reduce(double prevReturn, double current);

	protected abstract long reduce(long prevReturn, long current);

	@Override
	public void init(Object[] args) throws Exception {
	}
	
	@Override
	public int getNumberOfArgs() {
		return 0;
	}
}