# Copyright 2015 VMware, Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, without warranties or
# conditions of any kind, EITHER EXPRESS OR IMPLIED. See the License for the
# specific language governing permissions and limitations under the License.

module EsxCloud
  class SecurityGroup

    attr_accessor :name, :is_inherited

    # @param [String]
    # @param [Boolean]
    def initialize(name, is_inherited)
      @name = name
      @is_inherited = is_inherited
    end

    # @param [String] json
    # @return [SecurityGroup]
    def self.create_from_json(json)
      create_from_hash(JSON.parse(json))
    end

    # @param [Hash] hash
    # @return [SecurityGroup]
    def self.create_from_hash(hash)
      unless hash.is_a?(Hash) && hash.keys.to_set.superset?(%w(name isInherited).to_set)
        fail UnexpectedFormat, "Invalid SecurityGroup hash: #{hash}"
      end

      new(hash["name"], hash["isInherited"])
    end

    def ==(other)
      @name==other.id && @is_inherited==is_inherited
    end
  end
end
