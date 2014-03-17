/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hoya.core.launch;

import com.google.common.base.Preconditions;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hoya.tools.HoyaUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Build a single command line to include in the container commands;
 */
public class CommandLineBuilder {
  protected final List<String> argumentList = new ArrayList<String>(20);


  /**
   * Add an entry to the command list
   * @param arg argument
   * @return the argument
   */
  public String add(String arg) {
    argumentList.add(arg);
    return arg;
  }

  /**
   * Get the value at an offset
   * @param offset offset
   * @return the value at that point
   */
  public String elt(int offset) {
    return argumentList.get(offset);
  }

  /**
   * Get the number of arguments
   * @return an integer >= 0
   */
  public int size() {
    return argumentList.size();
  }
  
  /**
   * Append the output and error files to the tail of the command
   * @param stdout out
   * @param stderr error. Set this to null to append into stdout
   */
  public void addOutAndErrFiles(String stdout, String stderr) {
    Preconditions.checkNotNull(stdout, "Null output file");
    Preconditions.checkState(!stdout.isEmpty(), "output filename invalid");
    // write out the path output
    argumentList.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" +
             stdout);
    if (stderr != null) {
      argumentList.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" +
               stderr);
    } else {
      argumentList.add("2>&1");
    }
  }

  /**
   * This just returns the command line
   * @see #build()
   * @return the command line
   */
  @Override
  public String toString() {
    return build();
  }

  /**
   * Build the command line
   * @return the command line
   */
  public String build() {
    return HoyaUtils.join(argumentList, " ");
  }

  public List<String> getArgumentList() {
    return argumentList;
  }
  
  
}
