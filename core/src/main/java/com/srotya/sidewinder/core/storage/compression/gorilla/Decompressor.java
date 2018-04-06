/** 
 * 	Copyright 2016-2018 Michael Burman and/or other contributors.
*
*   Licensed under the Apache License, Version 2.0 (the "License");
*   you may not use this file except in compliance with the License.
*   You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*   See the License for the specific language governing permissions and
*   limitations under the License.
**/  
package com.srotya.sidewinder.core.storage.compression.gorilla;

import com.srotya.sidewinder.core.storage.DataPoint;

/**
 * Decompresses a compressed stream created by the Compressor. Returns pairs of
 * timestamp and floating point value.
 *
 * @author Michael Burman
 */
public class Decompressor {

	private int storedLeadingZeros = Integer.MAX_VALUE;
	private int storedTrailingZeros = 0;
	private long storedVal = 0;
	private long storedTimestamp = 0;
	private long storedDelta = 0;

	private long blockTimestamp = 0;

	private boolean endOfStream = false;

	private BitInput in;

	public Decompressor(BitInput input) {
		in = input;
		readHeader();
	}

	private void readHeader() {
		blockTimestamp = in.getLong(64);
	}

	public long[] read() {
		next();
		if (endOfStream) {
			return null;
		}
		return new long[] { storedTimestamp, storedVal };
	}

	/**
	 * Returns the next pair in the time series, if available.
	 *
	 * @return Pair if there's next value, null if series is done.
	 */
	public DataPoint readPair() {
		next();
		if (endOfStream) {
			return null;
		}
		return new DataPoint(storedTimestamp, storedVal);
	}

	private void next() {
		if (storedTimestamp == 0) {
			// First item to read
			storedDelta = in.getLong(Compressor.FIRST_DELTA_BITS);
			if (storedDelta == (1 << 27) - 1) {
				endOfStream = true;
				return;
			}
			storedVal = in.getLong(64);
			storedTimestamp = blockTimestamp + storedDelta;
		} else {
			nextTimestamp();
		}
	}

	private int bitsToRead() {
		int val = in.nextClearBit(4);
		int toRead = 0;

		switch (val) {
		case 0x00:
			break;
		case 0x02:
			toRead = 7; // '10'
			break;
		case 0x06:
			toRead = 9; // '110'
			break;
		case 0x0e:
			toRead = 12;
			break;
		case 0x0F:
			toRead = 32;
			break;
		}

		return toRead;
	}

	private void nextTimestamp() {
		// Next, read timestamp
		long deltaDelta = 0;
		int toRead = bitsToRead();
		if (toRead > 0) {
			deltaDelta = in.getLong(toRead);

			if (toRead == 32) {
				if ((int) deltaDelta == 0xFFFFFFFF) {
					// End of stream
					endOfStream = true;
					return;
				}
			} else {
				// Turn "unsigned" long value back to signed one
				if (deltaDelta > (1 << (toRead - 1))) {
					deltaDelta -= (1 << toRead);
				}
			}

			deltaDelta = (int) deltaDelta;
		}

		storedDelta = storedDelta + deltaDelta;
		storedTimestamp = storedDelta + storedTimestamp;
		nextValue();
	}

	private void nextValue() {
		// Read value
		if (in.readBit()) {
			// else -> same value as before
			if (in.readBit()) {
				// New leading and trailing zeros
				storedLeadingZeros = (int) in.getLong(5);

				byte significantBits = (byte) in.getLong(6);
				if (significantBits == 0) {
					significantBits = 64;
				}
				storedTrailingZeros = 64 - significantBits - storedLeadingZeros;
			}
			long value = in.getLong(64 - storedLeadingZeros - storedTrailingZeros);
			value <<= storedTrailingZeros;
			value = storedVal ^ value;
			storedVal = value;
		}
	}

}