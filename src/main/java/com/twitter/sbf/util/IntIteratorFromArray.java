/**
 * Copyright 2020 Twitter. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.sbf.util;

import it.unimi.dsi.fastutil.ints.IntIterator;

public class IntIteratorFromArray implements IntIterator {
  private int[] arr;
  private int pos;

  public IntIteratorFromArray(int[] arr) {
    this.pos = 0;
    this.arr = arr;
  }

  @Override
  public int nextInt() {
    return arr[pos++];
  }

  @Override
  public int skip(int i) {
    pos += i;
    return arr[pos++];
  }

  @Override
  public boolean hasNext() {
    return pos < arr.length;
  }

  @Override
  public Integer next() {
    return nextInt();
  }
}
