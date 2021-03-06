/*
 * Copyright (C) 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package test.staticprovides;

import dagger.Component;

/**
 * A simple component that demonstrates both static and non-static provides methods with a builder.
 */
@Component(modules = {AllStaticModule.class, SomeStaticModule.class})
interface StaticTestComponentWithBuilder extends StaticTestComponent {
  @Component.Builder
  interface Builder {
    Builder allStaticModule(AllStaticModule allStaticModule);
    Builder someStaticModule(SomeStaticModule someStaticModule);
    StaticTestComponentWithBuilder build();
  }
}
